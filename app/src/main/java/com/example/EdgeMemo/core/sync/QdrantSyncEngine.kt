package com.example.EdgeMemo.core.sync

import com.example.EdgeMemo.core.record.Record

/**
 * The Qdrant-native synchronization engine contract (Phase 12 §28).
 *
 * Everything durable lives in Qdrant Edge: knowledge records, operation
 * records, system cursor records. Room/SQLite/files/in-memory state are NOT
 * part of this path.
 */
interface QdrantSyncEngine {

    /**
     * 12B.7 — run change detection for one locally written record and, when
     * policy and versions require it, enqueue the deterministic operation
     * identity into the Qdrant-native operation store. Sends nothing.
     */
    suspend fun enqueueIfChanged(record: Record): ChangeDetectionOutcome

    /**
     * 12B.8 — drain at most [maxOperations] claimable operations to the
     * cloud with leases; ACK / retry / DEAD transitions are persisted to the
     * Qdrant-native store. Returns honest counts.
     */
    suspend fun pushPending(maxOperations: Int): SyncRunSummary

    /**
     * 12B.9 — pull one page of cloud knowledge, classify each item against
     * local Qdrant state (§12), apply deterministically, then advance the
     * persisted cursor. Cloud-applied records never generate outgoing
     * operations (§13).
     */
    suspend fun pullAndApply(pageSize: Int): SyncPullSummary

    /**
     * 12B.11 — bounded, indexed §19 reconciliation passes (R1–R5). Recovers
     * work lost at every crash boundary using durable intermediate states,
     * deterministic identities and idempotent replay. Never scans
     * unfiltered; never invents a transition the state machine forbids.
     */
    suspend fun reconcile(): ReconciliationSummary
}

/**
 * Honest outcome counters for one reconciliation invocation (12A §19).
 * Every field is a count of durable state transitions/observations made
 * while recovering from process death at a known crash boundary.
 */
data class ReconciliationSummary(
    /** R1: lease-expired IN_FLIGHT operations made claimable again. */
    val staleInFlightRecovered: Int = 0,
    /** R2: operations re-enqueued for PENDING records that had none. */
    val missingOperationsEnqueued: Int = 0,
    /** R3: operations whose knowledge record is absent (reported, not deleted). */
    val orphanedOperationsReported: Int = 0,
    /** R4: TOMBSTONE operations enqueued for unpropagated tombstones. */
    val tombstonePropagationEnqueued: Int = 0,
    /** R5: records whose ACKED operation had no persisted watermark. */
    val acknowledgementsRepaired: Int = 0,
    val recordsScanned: Int = 0,
    val operationsScanned: Int = 0,
    /** Operations still durable and claimable after reconciliation. */
    val claimableOperationsRemaining: Long = 0,
) {
    val didRepairAnything: Boolean
        get() = staleInFlightRecovered > 0 || missingOperationsEnqueued > 0 ||
            tombstonePropagationEnqueued > 0 || acknowledgementsRepaired > 0
}

/** Honest outcome counters for one push drain; nothing is fabricated. */
data class SyncRunSummary(
    val processed: Int = 0,
    val acked: Int = 0,
    val failed: Int = 0,
    val dead: Int = 0,
    val stale: Int = 0,
    val conflicts: Int = 0,
    val recovered: Int = 0,
    /** Retryable/claimable work still durable in the store after this drain. */
    val remaining: Long = 0,
)

/** Honest outcome counters for one pull page. */
data class SyncPullSummary(
    val applied: Int = 0,
    val duplicates: Int = 0,
    val stale: Int = 0,
    val conflicts: Int = 0,
    val tombstoned: Int = 0,
    val rejected: Int = 0,
    val nextCursor: String? = null,
) {
    val hasMore: Boolean get() = nextCursor != null
}
