package com.example.EdgeMemo.domain.sync

import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.sync.SyncSummary

/**
 * Atomic memory + outbox persistence. A memory row and its corresponding
 * outbox row are written in a single Room transaction so the two can never
 * diverge. No network work is performed here.
 *
 * Returns `true` when a syncable payload was enqueued (the memory's
 * `syncState` is then `PENDING`); `false` for `LOCAL_ONLY` memories that must
 * contribute nothing to the outbox.
 */
interface SyncOutboxWriter {
    suspend fun insertMemory(memory: Memory): Boolean

    suspend fun insertMemories(memories: List<Memory>): List<Boolean>

    suspend fun updateMemory(memory: Memory): Boolean

    /** A local delete also removes the memory's pending sync operations. */
    suspend fun cancelForMemory(memoryId: String)

    /** Outbox-derived counts for the UI (pending/syncing/synced/failed). */
    suspend fun outboxCounts(): SyncSummary
}