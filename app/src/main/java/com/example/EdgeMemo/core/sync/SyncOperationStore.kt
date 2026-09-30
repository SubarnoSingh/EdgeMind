package com.example.EdgeMemo.core.sync

import com.example.EdgeMemo.core.record.RecordId

/**
 * Durable, Qdrant-native store for synchronization operation records.
 *
 * Operations are version-scoped and immutable after reaching a terminal
 * state ([OutboxOperationState.ACKED] / [OutboxOperationState.DEAD]): a new
 * local change creates a new operation identity rather than mutating a
 * terminal operation (Phase 12 §9.3).
 *
 * The production implementation persists operations as payload-only points
 * in the same Qdrant Edge shard as knowledge records (Phase 12 §20.1); no
 * Room, SQLite, files or in-memory state back this interface.
 */
interface SyncOperationStore {

    /**
     * Persist a new PENDING operation. Idempotent by [SyncOperationId]:
     * enqueueing an operation identity that already exists returns the
     * stored operation and never rewrites it.
     */
    suspend fun enqueue(operation: SyncOperationRecord): SyncOperationRecord

    /** Exact idempotency lookup on the indexed `operation_id` field. */
    suspend fun findByOperationId(operationId: SyncOperationId): SyncOperationRecord?

    /**
     * Claim up to [limit] claimable operations for delivery.
     *
     * Claimable: `PENDING`, `FAILED` (re-claim), and `IN_FLIGHT` whose lease
     * expired before `now` (process-death recovery — the lease refresh keeps
     * the operation IN_FLIGHT rather than inventing an illegal transition).
     * A bounded candidate page is sorted client-side by `createdAt` (FIFO
     * fairness, Phase 12 §21.2) before claiming.
     */
    suspend fun claimNext(limit: Int, leaseMs: Long): List<SyncOperationRecord>

    /** Terminal ACK. Only an IN_FLIGHT operation can be acknowledged. */
    suspend fun markAcked(operationId: SyncOperationId, cloudVersion: Int): Boolean

    /** Retryable failure. Only an IN_FLIGHT operation can fail retryably. */
    suspend fun markFailed(operationId: SyncOperationId, kind: SyncFailureKind): Boolean

    /** Permanent failure. Only an IN_FLIGHT operation can die. */
    suspend fun markDead(operationId: SyncOperationId, kind: SyncFailureKind): Boolean

    /**
     * Move IN_FLIGHT operations whose lease expired before [now] to FAILED so
     * the next claim re-delivers them with the identical operation id
     * (Phase 12 §18 case 3). Returns the number of recovered operations.
     */
    suspend fun recoverStaleInFlight(now: Long): Int

    /** Highest record version enqueued for [recordId], or null if none. */
    suspend fun maxOperationVersionFor(recordId: RecordId): Int?

    /** Count of operations currently in [state] (indexed). */
    suspend fun countByState(state: OutboxOperationState): Long

    /**
     * Paginated, indexed listing of operations in any of [states], ordered by
     * the store's internal page position. [offsetId] resumes from a previous
     * page's offset id; null starts from the beginning.
     */
    suspend fun listByStates(
        states: Set<OutboxOperationState>,
        limit: Int,
        offsetId: String?,
    ): SyncOperationPage

    /** Force durability of all writes so far. */
    suspend fun flush()
}

/** One page of operation records for pagination. */
data class SyncOperationPage(
    val operations: List<SyncOperationRecord>,
    val nextOffsetId: String?,
)
