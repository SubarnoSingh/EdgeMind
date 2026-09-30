package com.example.EdgeMemo.data.sync

import com.example.EdgeMemo.core.record.LocalRecordStore
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.core.sync.SyncOperationStore
import com.example.EdgeMemo.core.sync.SyncSummary
import com.example.EdgeMemo.domain.sync.SyncStatusReader

/**
 * Phase 13.2 — sync status for the UI read from the Qdrant-native operation
 * store (the same durable points the sync engine mutates). No Room, no cache,
 * no in-memory counters: whatever the chip shows is indexed truth about the
 * application shard's operation state.
 *
 * The store handle is shared with the repository and the sync runtime;
 * `ensureReady`/`ensureIndexes` are idempotent, so reading before any write
 * opens the same single shard rather than assuming one. The `localOnly` field
 * is intentionally left zero here — the ViewModel computes it from the loaded
 * memories, exactly as before the cutover.
 */
class QdrantSyncStatusReader(
    private val recordStore: LocalRecordStore,
    private val operations: SyncOperationStore,
    private val dimension: Int,
) : SyncStatusReader {

    override suspend fun outboxCounts(): SyncSummary {
        recordStore.ensureReady(dimension)
        recordStore.ensureIndexes()
        val pending = operations.countByState(OutboxOperationState.PENDING)
        val syncing = operations.countByState(OutboxOperationState.IN_FLIGHT)
        val synced = operations.countByState(OutboxOperationState.ACKED)
        val failed = operations.countByState(OutboxOperationState.FAILED) +
            operations.countByState(OutboxOperationState.DEAD)
        return SyncSummary(pending = pending, syncing = syncing, synced = synced, failed = failed)
    }
}
