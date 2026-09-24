package com.example.EdgeMemo.data.sync

import androidx.room.withTransaction
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.policy.SyncPayloadFactory
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.core.sync.OutboxOperationType
import com.example.EdgeMemo.core.sync.SyncSummary
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.local.room.MemoryDao
import com.example.EdgeMemo.data.local.room.MemoryMappers.toEntity
import com.example.EdgeMemo.data.local.room.SyncOutboxDao
import com.example.EdgeMemo.data.local.room.SyncOutboxEntity
import com.example.EdgeMemo.domain.sync.SyncOutboxWriter

/**
 * Atomic memory + outbox persistence through one Room transaction.
 *
 * [SyncPayloadFactory] is the ONLY authority on what enters the outbox: a
 * `LOCAL_ONLY` memory produces no row and its pre-existing (now-overridden)
 * operations are removed; a `SYNC` memory stores the allowed original
 * representation; a `SYNC_REDACTED` memory stores only the redacted
 * representation already computed at policy time.
 *
 * `operationId` is deterministic (`UPSERT-<memoryId>`) and stable across
 * retries and re-enqueues; re-enqueuing refreshes the payload (latest wins)
 * and revives `PENDING`, never clobbers an in-flight claim, and never resets
 * `attempts`.
 */
class RoomSyncOutboxWriter(
    private val database: EdgeMindDatabase,
    private val memoryDao: MemoryDao,
    private val outboxDao: SyncOutboxDao,
) : SyncOutboxWriter {

    override suspend fun insertMemory(memory: Memory): Boolean = database.withTransaction {
        memoryDao.insert(memory.toEntity())
        enqueueLocked(memory)
    }

    override suspend fun insertMemories(memories: List<Memory>): List<Boolean> = database.withTransaction {
        memoryDao.insertAll(memories.map { it.toEntity() })
        memories.map { enqueueLocked(it) }
    }

    override suspend fun updateMemory(memory: Memory): Boolean = database.withTransaction {
        memoryDao.update(memory.toEntity())
        enqueueLocked(memory)
    }

    override suspend fun cancelForMemory(memoryId: String) {
        outboxDao.deleteByMemoryId(memoryId)
    }

    override suspend fun outboxCounts(): SyncSummary {
        val pending = outboxDao.countByState(OutboxOperationState.PENDING.name)
        val syncing = outboxDao.countByState(OutboxOperationState.IN_FLIGHT.name)
        val synced = outboxDao.countByState(OutboxOperationState.ACKED.name)
        val failed = outboxDao.countByState(OutboxOperationState.FAILED.name) +
            outboxDao.countByState(OutboxOperationState.DEAD.name)
        return SyncSummary(
            pending = pending,
            syncing = syncing,
            synced = synced,
            failed = failed,
        )
    }

    /** Callers are already inside `withTransaction` when reaching here. */
    private suspend fun enqueueLocked(memory: Memory): Boolean {
        val payload = SyncPayloadFactory.build(memory) ?: run {
            // A prior decision may have allowed sync; the latest decision is
            // LOCAL_ONLY, so anything previously enqueued must be withdrawn.
            outboxDao.deleteByMemoryId(memory.memoryId)
            memoryDao.updateSyncState(memory.memoryId, MemorySyncState.LOCAL.name)
            return false
        }

        val operationId = operationIdFor(memory.memoryId)
        val refreshed = outboxDao.refreshPayload(operationId, payload.title, payload.content)
        if (refreshed == 0) {
            // First time this memory is enqueued: latest-wins payload above,
            // fresh createdAt, zero attempts, PENDING.
            outboxDao.insertOrIgnore(
                SyncOutboxEntity(
                    operationId = operationId,
                    memoryId = memory.memoryId,
                    operationType = OutboxOperationType.UPSERT.name,
                    payloadTitle = payload.title,
                    payloadContent = payload.content,
                    createdAt = now(),
                    attempts = 0,
                    state = OutboxOperationState.PENDING.name,
                    lastError = null,
                ),
            )
        }
        memoryDao.updateSyncState(memory.memoryId, MemorySyncState.PENDING.name)
        return true
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        /** Deterministic, stable idempotency key (spec §31). */
        const val OPERATION_PREFIX = "UPSERT"

        fun operationIdFor(memoryId: String): String = "$OPERATION_PREFIX-$memoryId"
    }
}