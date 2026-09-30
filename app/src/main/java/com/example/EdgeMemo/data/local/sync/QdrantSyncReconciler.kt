package com.example.EdgeMemo.data.local.sync

import com.example.EdgeMemo.core.record.LocalRecordStore
import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordFilter
import com.example.EdgeMemo.core.record.RecordQuery
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.record.SyncState
import com.example.EdgeMemo.core.sync.CanonicalContentHash
import com.example.EdgeMemo.core.sync.ChangeDetectionOutcome
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.core.sync.ReconciliationSummary
import com.example.EdgeMemo.core.sync.SyncOperationId
import com.example.EdgeMemo.core.sync.SyncOperationStore
import com.example.EdgeMemo.core.sync.SyncOperationType

/**
 * Phase 12B.11 — Qdrant-native crash recovery / reconciliation (12A §19).
 *
 * qdrant-edge provides no transactions and no CAS (VERIFIED §10.7/§16). The
 * recovery strategy is the one the architecture mandates: durable
 * intermediate states + deterministic identities + idempotent replay. Every
 * pass here is bounded, indexed, and runs at most once per invocation; no
 * pass scans the collection unfiltered.
 *
 * | Pass | Recovers                                                       |
 * |------|----------------------------------------------------------------|
 * | R1   | process died while IN_FLIGHT → lease expired → FAILED, reclaimable |
 * | R2   | record flushed as PENDING but its operation never persisted        |
 * | R3   | operation whose knowledge record no longer exists (report only)    |
 * | R4   | tombstone persisted but not yet propagated (missing TOMBSTONE op)  |
 * | R5   | operation ACKED but the record watermark write was lost            |
 *
 * R2/R4 reuse the 12B.7 detector, so policy gates (LOCAL_ONLY, redaction,
 * cloud-origin echo guard) and identity idempotency hold during recovery too.
 * R3 never force-transitions an operation: PENDING → DEAD is illegal (§5.3),
 * so orphaned work is reported for the maintenance surface, not invented away.
 */
class QdrantSyncReconciler(
    private val recordStore: LocalRecordStore,
    private val operationStore: SyncOperationStore,
    private val detector: QdrantChangeDetector,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun reconcile(now: Long = clock()): ReconciliationSummary {
        val recovered = operationStore.recoverStaleInFlight(now)

        // R2 + R5 — PENDING records. Enqueue missing deterministic operations;
        // if the (record, version) operation is already ACKED but the record
        // still says PENDING, the ACK→watermark write was lost: repair it.
        var enqueued = 0
        var repaired = 0
        var recordsScanned = 0
        var offset: String? = null
        while (true) {
            val page = recordStore.scroll(
                RecordQuery.Scroll(
                    filter = RecordFilter.syncState(SyncState.PENDING),
                    limit = PAGE_SIZE,
                    offsetId = offset,
                    recordTypes = RecordType.KNOWLEDGE_TYPES,
                ),
            )
            for (record in page.records) {
                recordsScanned++
                val acked = findAckedOperation(record)
                if (acked != null) {
                    repairWatermark(record, acked)
                    repaired++
                    continue
                }
                when (val outcome = detector.detectAndEnqueue(record)) {
                    is ChangeDetectionOutcome.Enqueued -> enqueued++
                    else -> Unit // AlreadyEnqueued / NoChange / policy — detector is honest
                }
            }
            if (page.nextOffsetId == null || page.records.isEmpty()) break
            offset = page.nextOffsetId
        }

        // R4 — tombstoned but not synced, syncable (§10.2 tombstone queue).
        var tombstonesPropagated = 0
        offset = null
        while (true) {
            val page = recordStore.scroll(
                RecordQuery.Scroll(
                    filter = RecordFilter.and(
                        RecordFilter.deletedOnly(),
                        RecordFilter.anyOf(
                            "_sync_state",
                            listOf(SyncState.LOCAL.name, SyncState.PENDING.name, SyncState.FAILED.name),
                        ),
                    ),
                    limit = PAGE_SIZE,
                    offsetId = offset,
                    recordTypes = RecordType.KNOWLEDGE_TYPES,
                ),
            )
            for (record in page.records) {
                val opType = if (record.tombstone) SyncOperationType.TOMBSTONE else SyncOperationType.UPSERT
                val existing = operationStore.findByOperationId(
                    SyncOperationId.generate(opType, record.id, record.version),
                )
                if (existing != null) continue
                when (detector.detectAndEnqueue(record)) {
                    is ChangeDetectionOutcome.Enqueued -> tombstonesPropagated++
                    else -> Unit
                }
            }
            if (page.nextOffsetId == null || page.records.isEmpty()) break
            offset = page.nextOffsetId
        }

        // R3 — orphaned operations: knowledge record absent. Reported, never
        // silently deleted and never force-transitioned (illegal transitions).
        var orphaned = 0
        var operationsScanned = 0
        offset = null
        while (true) {
            val page = operationStore.listByStates(
                states = setOf(
                    OutboxOperationState.PENDING,
                    OutboxOperationState.FAILED,
                    OutboxOperationState.IN_FLIGHT,
                ),
                limit = PAGE_SIZE,
                offsetId = offset,
            )
            for (op in page.operations) {
                operationsScanned++
                if (!recordStore.exists(op.recordId)) orphaned++
            }
            if (page.nextOffsetId == null || page.operations.isEmpty()) break
            offset = page.nextOffsetId
        }

        return ReconciliationSummary(
            staleInFlightRecovered = recovered,
            missingOperationsEnqueued = enqueued,
            orphanedOperationsReported = orphaned,
            tombstonePropagationEnqueued = tombstonesPropagated,
            acknowledgementsRepaired = repaired,
            recordsScanned = recordsScanned,
            operationsScanned = operationsScanned,
            claimableOperationsRemaining =
                operationStore.countByState(OutboxOperationState.PENDING) +
                    operationStore.countByState(OutboxOperationState.FAILED) +
                    operationStore.countByState(OutboxOperationState.IN_FLIGHT),
        )
    }

    /** The record's own version, either operation identity, ACKED. */
    private suspend fun findAckedOperation(record: Record): com.example.EdgeMemo.core.sync.SyncOperationRecord? {
        for (type in arrayOf(SyncOperationType.UPSERT, SyncOperationType.TOMBSTONE)) {
            val op = operationStore.findByOperationId(
                SyncOperationId.generate(type, record.id, record.version),
            )
            if (op != null && op.state == OutboxOperationState.ACKED) return op
        }
        return null
    }

    private suspend fun repairWatermark(record: Record, acked: com.example.EdgeMemo.core.sync.SyncOperationRecord) {
        val deviceIdentity = CanonicalContentHash.hash(record.payload)
        recordStore.upsert(
            record.copy(
                syncState = SyncState.SYNCED,
                contentHash = deviceIdentity,
                lastSyncedVersion = acked.version,
                lastSyncedOperationId = acked.operationId.value,
                lastSyncedContentHash = deviceIdentity,
                updatedAt = clock(),
            ),
        )
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}
