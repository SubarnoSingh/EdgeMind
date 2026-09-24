package com.example.EdgeMemo.data.sync

import androidx.room.withTransaction
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemorySensitivity
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.core.sync.OutboxOperationType
import com.example.EdgeMemo.core.sync.SyncFailureKind
import com.example.EdgeMemo.core.sync.SyncOperation
import com.example.EdgeMemo.core.sync.SyncPushResult
import com.example.EdgeMemo.core.sync.SyncSummary
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.local.room.MemoryDao
import com.example.EdgeMemo.data.local.room.SyncOutboxDao
import com.example.EdgeMemo.data.local.room.SyncOutboxEntity
import com.example.EdgeMemo.domain.sync.SyncEngine
import com.example.EdgeMemo.domain.sync.SyncRemoteDataSource
import com.example.EdgeMemo.domain.sync.SyncRunSummary

/**
 * Drives the durable outbox state machine. Design notes:
 *
 * - Crash recovery: `IN_FLIGHT` rows are retryable candidates. A worker that
 *   died after a remote success but before the local ACK therefore re-applies
 *   the (idempotent) operation instead of being lost forever. Server-side
 *   idempotency keyed on `operationId` is a Phase 7 requirement and is
 *   documented here until a real backend acknowledges it.
 * - Single-winner claims: every row is claimed with a guarded
 *   `UPDATE … WHERE state = expected`, so concurrent/duplicate worker
 *   executions can never double-process an operation.
 * - The network call sits outside any Room transaction (per spec).
 * - `ACKED` is written only when the remote returned `Success`.
 */
class DefaultSyncEngine(
    private val outboxDao: SyncOutboxDao,
    private val memoryDao: MemoryDao,
    private val remote: SyncRemoteDataSource,
    private val database: EdgeMindDatabase,
) : SyncEngine {

    override suspend fun processPending(maxOperations: Int): SyncRunSummary {
        // Crash recovery first: nothing may linger IN_FLIGHT across processes.
        outboxDao.recoverStaleInFlight()
        val candidates = outboxDao.selectRetryable(maxOperations)
        var claimed = 0
        var acked = 0
        var deferred = 0
        var dead = 0

        for (op in candidates) {
            val newAttempts = op.attempts + 1
            // Guarded single-winner claim on PENDING/FAILED.
            val locked = outboxDao.claim(op.operationId)
            if (locked == 0) {
                // Another worker already owns this row. Skip — the winner will
                // process it (no duplicate execution within this run).
                continue
            }
            claimed++

            val exhausted = newAttempts > MAX_ATTEMPTS
            if (exhausted) {
                markDead(op, given = SyncFailureKind.NETWORK)
                dead++
                continue
            }

            val result = pushSafely(op, newAttempts)
            when (result) {
                is SyncPushResult.Success -> {
                    database.withTransaction {
                        outboxDao.markAcknowledged(op.operationId)
                        memoryDao.updateSyncState(op.memoryId, MemorySyncState.SYNCED.name)
                    }
                    acked++
                }
                is SyncPushResult.Failure -> {
                    when {
                        isPermanent(result.kind) -> {
                            markDead(op, given = result.kind)
                            dead++
                        }
                        else -> {
                            outboxDao.markState(
                                op.operationId,
                                OutboxOperationState.FAILED.name,
                                result.kind.name,
                            )
                            deferred++
                        }
                    }
                }
            }
        }

        val remaining =
            outboxDao.countByState(OutboxOperationState.PENDING.name).toInt() +
                outboxDao.countByState(OutboxOperationState.FAILED.name).toInt()
        return SyncRunSummary(
            claimed = claimed,
            acked = acked,
            deferredForRetry = deferred,
            dead = dead,
            remaining = remaining,
        )
    }

    override suspend fun summary(): SyncSummary {
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

    /** The network call must never run inside a Room transaction. */
    private suspend fun pushSafely(
        op: SyncOutboxEntity,
        attempts: Int,
    ): SyncPushResult = try {
        remote.push(
            buildOperation(op),
        )
    } catch (e: Exception) {
        // Unexpected local/transport error → classified, never content-bearing.
        SyncPushResult.Failure(SyncFailureKind.NETWORK)
    }

    /**
     * Enriches the outbox payload with the evolving-memory metadata from the
     * Room row so a real backend can store the full cloud schema. The payload
     * itself is untouched: only the SyncPayloadFactory-sanctioned
     * title/content may leave the device.
     */
    private suspend fun buildOperation(op: SyncOutboxEntity): SyncOperation {
        val row = memoryDao.getById(op.memoryId)
        val memory = row?.let { entity ->
            runCatching {
                Memory(
                    memoryId = entity.memoryId,
                    title = entity.title,
                    content = entity.content,
                    chunkId = entity.chunkId,
                    source = entity.source,
                    type = enumOf<MemoryType>(entity.type) ?: MemoryType.NOTE,
                    tags = entity.tags,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt,
                    origin = enumOf<MemoryOrigin>(entity.origin) ?: MemoryOrigin.LOCAL,
                    syncDecision = enumOf<SyncDecision>(entity.syncDecision) ?: SyncDecision.SYNC,
                    syncState = enumOf<MemorySyncState>(entity.syncState) ?: MemorySyncState.LOCAL,
                    sensitivity = enumOf<MemorySensitivity>(entity.sensitivity) ?: MemorySensitivity.STANDARD,
                    importance = entity.importance,
                    version = entity.version,
                    contentHash = entity.contentHash,
                    subjectKey = entity.subjectKey,
                    supersedes = entity.supersedes,
                    tombstone = entity.tombstone,
                    metadata = entity.metadata,
                    policyReason = entity.policyReason,
                    redactedTitle = entity.redactedTitle,
                    redactedContent = entity.redactedContent,
                    authority = entity.authority,
                )
            }.getOrNull()
        }
        return SyncOperation(
            operationId = op.operationId,
            memoryId = op.memoryId,
            operationType = OutboxOperationType.valueOf(op.operationType),
            title = op.payloadTitle,
            content = op.payloadContent,
            syncDecision = memory?.syncDecision ?: SyncDecision.SYNC,
            origin = memory?.origin ?: MemoryOrigin.LOCAL,
            redacted = memory?.syncDecision == SyncDecision.SYNC_REDACTED,
            version = memory?.version ?: 1,
            contentHash = memory?.contentHash ?: "",
            subjectKey = memory?.subjectKey,
            type = memory?.type ?: MemoryType.NOTE,
            tags = memory?.tags.orEmpty(),
            chunkId = memory?.chunkId,
            source = memory?.source ?: "USER_ENTRY",
            supersedes = memory?.supersedes,
            tombstone = memory?.tombstone ?: false,
            updatedAt = memory?.updatedAt ?: op.createdAt,
            metadata = memory?.metadata.orEmpty(),
        )
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String): E? =
        enumValues<E>().firstOrNull { it.name == name }

    private suspend fun markDead(op: SyncOutboxEntity, given: SyncFailureKind) {
        database.withTransaction {
            outboxDao.markState(op.operationId, OutboxOperationState.DEAD.name, given.name)
            memoryDao.updateSyncState(op.memoryId, MemorySyncState.FAILED.name)
        }
    }

    private fun isPermanent(kind: SyncFailureKind): Boolean =
        kind == SyncFailureKind.REJECTED || kind == SyncFailureKind.UNAUTHORIZED

    companion object {
        const val MAX_ATTEMPTS = 5
    }
}