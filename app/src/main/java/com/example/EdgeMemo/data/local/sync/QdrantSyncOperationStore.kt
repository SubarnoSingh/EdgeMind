package com.example.EdgeMemo.data.local.sync

import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordFilter
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordQuery
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.core.sync.SyncFailureKind
import com.example.EdgeMemo.core.sync.SyncOperationId
import com.example.EdgeMemo.core.sync.SyncOperationPage
import com.example.EdgeMemo.core.sync.SyncOperationRecord
import com.example.EdgeMemo.core.sync.SyncOperationStore
import com.example.EdgeMemo.core.sync.SyncOperationTransitions
import com.example.EdgeMemo.core.record.LocalRecordStore
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Production [SyncOperationStore]: operations are persisted as payload-only
 * Qdrant points in the same Edge shard as knowledge records (Phase 12 §20.1),
 * reached exclusively through the Phase 11 [LocalRecordStore] boundary.
 *
 * Durability model (VERIFIED qdrant-edge constraints):
 *  - no transactions/CAS exist; correctness relies on the single-writer rule
 *    ([LocalRecordStore] serializes access) plus deterministic point ids.
 *  - The point id is derived from the operation identity
 *    (`UUID.nameUUIDFromBytes`), so re-enqueueing the same operation targets
 *    the same point and duplicate delivery is idempotent without a read race.
 *  - [QdrantEdgeRecordStore] flushes after every write, so a claim survives
 *    process death.
 */
class QdrantSyncOperationStore(
    private val recordStore: LocalRecordStore,
    private val now: () -> Long = System::currentTimeMillis,
) : SyncOperationStore {

    override suspend fun enqueue(operation: SyncOperationRecord): SyncOperationRecord {
        require(operation.state == OutboxOperationState.PENDING) {
            "Only PENDING operations can be enqueued, got ${operation.state}"
        }
        // Immutable identity: if this operation already exists, the stored
        // operation wins. A terminal operation is never rewritten (§9.3).
        findByOperationId(operation.operationId)?.let { existing -> return existing }
        recordStore.upsert(toRecord(operation))
        return operation
    }

    override suspend fun findByOperationId(operationId: SyncOperationId): SyncOperationRecord? {
        val page = recordStore.scroll(
            RecordQuery.Scroll(
                filter = RecordFilter.match(OPERATION_ID_FIELD, operationId.value),
                limit = 1,
                recordTypes = setOf(RecordType.OUTBOX_OP),
            ),
        )
        return page.records.firstOrNull()?.let(::toOperation)
    }

    override suspend fun claimNext(limit: Int, leaseMs: Long): List<SyncOperationRecord> {
        require(limit > 0) { "Claim limit must be positive" }
        require(leaseMs > 0) { "Lease duration must be positive" }
        val claimInstant = now()
        val candidates = recordStore.scroll(
            RecordQuery.Scroll(
                filter = claimableFilter(claimInstant),
                limit = min(max(limit * CLAIM_CANDIDATE_FACTOR, MIN_CANDIDATE_WINDOW), MAX_CANDIDATE_WINDOW),
                recordTypes = setOf(RecordType.OUTBOX_OP),
            ),
        ).records
            .map(::toOperation)
            // FIFO fairness: order by creation time, then identity (§21.2).
            .sortedWith(compareBy({ it.createdAt }, { it.operationId.value }))

        val claimed = mutableListOf<SyncOperationRecord>()
        for (candidate in candidates) {
            if (claimed.size == limit) break
            // Re-read before writing: the page position may be stale.
            val current = findByOperationId(candidate.operationId) ?: continue
            val transition = claimTransition(current, claimInstant, leaseMs) ?: continue
            recordStore.upsert(toRecord(transition))
            claimed.add(transition)
        }
        return claimed
    }

    override suspend fun markAcked(operationId: SyncOperationId, cloudVersion: Int): Boolean =
        transition(operationId) { op ->
            require(cloudVersion >= 1) { "Cloud version must be at least 1" }
            SyncOperationTransitions.validate(op.state, OutboxOperationState.ACKED)
            op.copy(
                state = OutboxOperationState.ACKED,
                leaseUntil = null,
                lastSyncedVersion = cloudVersion,
                updatedAt = now(),
            )
        }

    override suspend fun markFailed(operationId: SyncOperationId, kind: SyncFailureKind): Boolean =
        transition(operationId) { op ->
            SyncOperationTransitions.validate(op.state, OutboxOperationState.FAILED)
            op.copy(
                state = OutboxOperationState.FAILED,
                attempts = op.attempts + 1,
                lastError = kind,
                leaseUntil = null,
                updatedAt = now(),
            )
        }

    override suspend fun markDead(operationId: SyncOperationId, kind: SyncFailureKind): Boolean =
        transition(operationId) { op ->
            SyncOperationTransitions.validate(op.state, OutboxOperationState.DEAD)
            op.copy(
                state = OutboxOperationState.DEAD,
                attempts = op.attempts + 1,
                lastError = kind,
                leaseUntil = null,
                updatedAt = now(),
            )
        }

    override suspend fun recoverStaleInFlight(now: Long): Int {
        val stale = recordStore.scroll(
            RecordQuery.Scroll(
                filter = RecordFilter.and(
                    RecordFilter.match(STATE_FIELD, OutboxOperationState.IN_FLIGHT.name),
                    RecordFilter.DateRange(LEASE_UNTIL_FIELD, before = now),
                ),
                limit = MAX_CANDIDATE_WINDOW,
                recordTypes = setOf(RecordType.OUTBOX_OP),
            ),
        ).records
            .map(::toOperation)
            .filter { it.state == OutboxOperationState.IN_FLIGHT && it.leaseUntil != null && it.leaseUntil!! < now }

        for (op in stale) {
            SyncOperationTransitions.validate(op.state, OutboxOperationState.FAILED)
            recordStore.upsert(
                toRecord(
                    op.copy(
                        state = OutboxOperationState.FAILED,
                        attempts = op.attempts + 1,
                        leaseUntil = null,
                        updatedAt = this.now(),
                    ),
                ),
            )
        }
        return stale.size
    }

    override suspend fun maxOperationVersionFor(recordId: RecordId): Int? {
        var max: Int? = null
        var offset: String? = null
        while (true) {
            val page = recordStore.scroll(
                RecordQuery.Scroll(
                    filter = RecordFilter.match(RECORD_ID_FIELD, recordId.uuid),
                    limit = PAGE_SIZE,
                    offsetId = offset,
                    recordTypes = setOf(RecordType.OUTBOX_OP),
                ),
            )
            page.records.map(::toOperation).forEach { op ->
                max = maxOf(max ?: op.version, op.version)
            }
            if (page.nextOffsetId == null || page.records.isEmpty()) break
            offset = page.nextOffsetId
        }
        return max
    }

    override suspend fun countByState(state: OutboxOperationState): Long =
        recordStore.count(
            RecordQuery.Count(
                filter = RecordFilter.match(STATE_FIELD, state.name),
                recordTypes = setOf(RecordType.OUTBOX_OP),
            ),
        )

    override suspend fun listByStates(
        states: Set<OutboxOperationState>,
        limit: Int,
        offsetId: String?,
    ): SyncOperationPage {
        require(states.isNotEmpty()) { "At least one state is required" }
        require(limit > 0) { "Page limit must be positive" }
        val page = recordStore.scroll(
            RecordQuery.Scroll(
                filter = RecordFilter.anyOf(STATE_FIELD, states.map { it.name }),
                limit = limit,
                offsetId = offsetId,
                recordTypes = setOf(RecordType.OUTBOX_OP),
            ),
        )
        return SyncOperationPage(page.records.map(::toOperation), page.nextOffsetId)
    }

    override suspend fun flush() {
        recordStore.flush()
    }

    private suspend fun transition(
        operationId: SyncOperationId,
        mutate: (SyncOperationRecord) -> SyncOperationRecord,
    ): Boolean {
        val op = findByOperationId(operationId) ?: return false
        val updated = runCatching { mutate(op) }.getOrNull() ?: return false
        recordStore.upsert(toRecord(updated))
        return true
    }

    /**
     * Compute the post-claim state for [op], or null when [op] is not
     * claimable at [instant]. Transitions are validated against the pure
     * 12B.1 state machine: PENDING → IN_FLIGHT, and FAILED re-claims via
     * FAILED → PENDING → IN_FLIGHT. A stale IN_FLIGHT lease is re-claimed
     * (recovery, not a transition — §5.3).
     */
    private fun claimTransition(
        op: SyncOperationRecord,
        instant: Long,
        leaseMs: Long,
    ): SyncOperationRecord? =
        when (op.state) {
            OutboxOperationState.PENDING -> {
                SyncOperationTransitions.validate(op.state, OutboxOperationState.IN_FLIGHT)
                claimed(op, instant, leaseMs)
            }
            OutboxOperationState.FAILED -> {
                SyncOperationTransitions.validate(op.state, OutboxOperationState.PENDING)
                SyncOperationTransitions.validate(OutboxOperationState.PENDING, OutboxOperationState.IN_FLIGHT)
                claimed(op, instant, leaseMs)
            }
            OutboxOperationState.IN_FLIGHT -> {
                val lease = op.leaseUntil
                if (lease != null && lease < instant) {
                    // Process-death recovery: stay IN_FLIGHT, refresh lease.
                    op.copy(
                        leaseUntil = instant + leaseMs,
                        attempts = op.attempts + 1,
                        updatedAt = instant,
                    )
                } else {
                    null
                }
            }
            OutboxOperationState.ACKED, OutboxOperationState.DEAD -> null
        }

    private fun claimed(op: SyncOperationRecord, instant: Long, leaseMs: Long): SyncOperationRecord =
        op.copy(
            state = OutboxOperationState.IN_FLIGHT,
            leaseUntil = instant + leaseMs,
            updatedAt = instant,
        )

    private fun claimableFilter(instant: Long): RecordFilter = RecordFilter.or(
        RecordFilter.anyOf(STATE_FIELD, listOf(OutboxOperationState.PENDING.name, OutboxOperationState.FAILED.name)),
        RecordFilter.and(
            RecordFilter.match(STATE_FIELD, OutboxOperationState.IN_FLIGHT.name),
            RecordFilter.DateRange(LEASE_UNTIL_FIELD, before = instant),
        ),
    )

    private fun toRecord(operation: SyncOperationRecord): Record {
        val domain = operation.toEnvelopeMap().filterKeys { it !in SHARED_ENVELOPE_KEYS }
        return Record(
            id = pointId(operation.operationId),
            recordType = RecordType.OUTBOX_OP,
            entityId = null,
            vector = null, // payload-only point: real qdrant-edge representation
            payload = domain,
            version = operation.version,
            createdAt = operation.createdAt,
            updatedAt = operation.updatedAt,
            syncDecision = operation.syncDecision,
            tombstone = false,
        )
    }

    private fun toOperation(record: Record): SyncOperationRecord {
        val full = record.toFullPayload().filterKeys { it !in RECORD_ONLY_KEYS }
        return SyncOperationRecord.fromEnvelopeMap(full)
    }

    companion object {
        private const val OPERATION_ID_FIELD = "operation_id"
        private const val STATE_FIELD = "_state"
        private const val RECORD_ID_FIELD = "_record_id"
        private const val LEASE_UNTIL_FIELD = "_lease_until"

        private const val PAGE_SIZE = 100
        private const val CLAIM_CANDIDATE_FACTOR = 4
        private const val MIN_CANDIDATE_WINDOW = 32
        private const val MAX_CANDIDATE_WINDOW = 256

        /** Envelope keys the Record envelope owns for operation points. */
        private val SHARED_ENVELOPE_KEYS = setOf(
            "_record_type",
            "_version",
            "_created_at",
            "_updated_at",
            "_sync_decision",
        )

        /** Record envelope fields that must not leak into operation decoding. */
        private val RECORD_ONLY_KEYS = setOf(
            "_schema_version",
            "_entity_id",
            "_source",
            "_sync_state",
            "_subject_key",
            "_content_hash",
            "_supersedes",
            "_tombstone",
            "_deleted_at",
            "_origin",
            "_authority",
            "_policy_reason",
            "_redacted_title",
            "_redacted_content",
            "_tags",
            "_metadata",
        )

        /**
         * Deterministic point id for one operation identity. qdrant-edge only
         * accepts UUID/u64 point ids, so the semantic `operation_id` lives in
         * an indexed payload field (§9.4 D) while the point id is derived —
         * never random — to make duplicate enqueue idempotent at the storage
         * layer as well.
         */
        @JvmStatic
        fun pointId(operationId: SyncOperationId): RecordId =
            RecordId.fromString(
                UUID.nameUUIDFromBytes(
                    "edgemind:sync-op:${operationId.value}".toByteArray(Charsets.UTF_8),
                ).toString(),
            )
    }
}
