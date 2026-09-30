package com.example.EdgeMemo.data.local.sync

import com.example.EdgeMemo.core.record.JsonString
import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.LocalRecordStore
import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordOrigin
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.record.SyncDecision
import com.example.EdgeMemo.core.record.SyncState
import com.example.EdgeMemo.core.sync.CanonicalContentHash
import com.example.EdgeMemo.core.sync.ChangeDetectionOutcome
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.core.sync.QdrantSyncEngine
import com.example.EdgeMemo.core.sync.QdrantSyncRemote
import com.example.EdgeMemo.core.sync.SyncClassification
import com.example.EdgeMemo.core.sync.SyncFailureKind
import com.example.EdgeMemo.core.sync.SyncOperationRecord
import com.example.EdgeMemo.core.sync.SyncOperationStore
import com.example.EdgeMemo.core.sync.SyncPullSummary
import com.example.EdgeMemo.core.sync.SyncPushOutcome
import com.example.EdgeMemo.core.sync.SyncRunSummary
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource

/**
 * Production [QdrantSyncEngine] for Phase 12B.7–12B.9.
 *
 * Every durable input and output is a Qdrant Edge point:
 *   knowledge records        — payload and/or vector points in the shard
 *   operation records        — via [SyncOperationStore] (payload-only points)
 *   pull cursor              — payload-only `sys_cursor` point
 *   recorded conflict        — payload-only `conflict` point
 *
 * Local → cloud (§11): claim with a lease, deliver the frozen Phase 12
 * envelope, persist ACK/FAILED/DEAD transitions and the §7.3 watermark.
 * Cloud → local (§12): pull the existing `GET /knowledge` contract, classify
 * with the frozen §7.1 matrix, apply idempotently, record (never resolve)
 * conflicts, advance the cursor only after the page was applied.
 *
 * 12B.10 resolution and 12B.11 reconciliation are separate collaborators
 * ([QdrantConflictResolver], [QdrantSyncReconciler]); WorkManager wiring
 * (12B.12) is intentionally absent from this layer.
 */
class DefaultQdrantSyncEngine(
    private val recordStore: LocalRecordStore,
    private val operationStore: SyncOperationStore,
    private val detector: QdrantChangeDetector,
    private val remote: QdrantSyncRemote,
    private val cloudKnowledge: CloudKnowledgeRemoteDataSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val conflicts: QdrantConflictRecorder = QdrantConflictRecorder(recordStore) { clock() },
    private val leaseMs: Long = 60_000L,
    private val maxAttempts: Int = 5,
    /**
     * Phase 13.4 — optional embedding hook for freshly pulled cloud items.
     * The 12B.9 engine was shard-pure and left new cloud points payload-only;
     * now that this path is the ACTIVE cloud source, dense retrieval parity
     * with the legacy writer requires a vector for new records. Vectors are
     * excluded from content identity (frozen 12B.2 rule), so this can never
     * affect hashing, echo immunity or operation semantics.
     */
    private val cloudEmbedding: (suspend (CloudKnowledgeItem) -> FloatArray?)? = null,
    private val reconciler: QdrantSyncReconciler = QdrantSyncReconciler(
        recordStore,
        operationStore,
        detector,
        clock,
    ),
) : QdrantSyncEngine {

    init {
        require(leaseMs > 0) { "Lease must be positive" }
        require(maxAttempts >= 1) { "Retry budget must be at least 1" }
    }

    // ------------------------------------------------------------------
    // 12B.7
    // ------------------------------------------------------------------

    override suspend fun enqueueIfChanged(record: Record): ChangeDetectionOutcome =
        detector.detectAndEnqueue(record)

    // ------------------------------------------------------------------
    // 12B.11 — crash recovery
    // ------------------------------------------------------------------

    override suspend fun reconcile(): com.example.EdgeMemo.core.sync.ReconciliationSummary =
        reconciler.reconcile()

    // ------------------------------------------------------------------
    // 12B.8 — local → cloud
    // ------------------------------------------------------------------

    override suspend fun pushPending(maxOperations: Int): SyncRunSummary {
        val start = clock()
        // §18 case 3: lease-expired work becomes claimable again before draining.
        val recovered = operationStore.recoverStaleInFlight(start)
        val claimed = operationStore.claimNext(maxOperations.coerceAtLeast(1), leaseMs)

        var acked = 0
        var failed = 0
        var dead = 0
        var stale = 0
        var conflictsCount = 0
        for (op in claimed) {
            when (val outcome = remote.push(op)) {
                is SyncPushOutcome.Applied -> {
                    val version = outcome.cloudVersion ?: op.version
                    if (operationStore.markAcked(op.operationId, version)) {
                        markRecordSynced(op, outcome.cloudContentHash)
                        acked++
                    }
                }
                is SyncPushOutcome.Duplicate -> {
                    // §18 case 4: the cloud already holds exactly this intent.
                    // Idempotent success — acknowledge, never re-deliver.
                    if (operationStore.markAcked(op.operationId, outcome.cloudVersion)) {
                        markRecordSynced(op, outcome.cloudContentHash)
                        acked++
                    }
                }
                is SyncPushOutcome.Stale -> {
                    // §11.1-I: the cloud is ahead. The operation identity can
                    // never succeed, so it dies — but the LOCAL record state
                    // is never silently adopted or overwritten. Reconciliation
                    // via pull happens through §12, not here.
                    stale++
                    if (operationStore.markDead(op.operationId, SyncFailureKind.REJECTED)) dead++
                }
                is SyncPushOutcome.Conflict -> {
                    // §23.1 row 3: preserve the evidence, do not auto-win.
                    conflictsCount++
                    recordPushConflict(op, outcome)
                    if (operationStore.markDead(op.operationId, SyncFailureKind.REJECTED)) dead++
                }
                is SyncPushOutcome.RetryableFailure -> {
                    // Budget exhausted → DEAD (§5.3); otherwise FAILED with
                    // retry metadata persisted durably.
                    if (op.attempts + 1 >= maxAttempts) {
                        if (operationStore.markDead(op.operationId, outcome.kind)) dead++
                    } else {
                        if (operationStore.markFailed(op.operationId, outcome.kind)) failed++
                    }
                }
                is SyncPushOutcome.PermanentFailure -> {
                    if (operationStore.markDead(op.operationId, outcome.kind)) dead++
                }
            }
        }

        val remaining = operationStore.countByState(OutboxOperationState.PENDING) +
            operationStore.countByState(OutboxOperationState.FAILED) +
            operationStore.countByState(OutboxOperationState.IN_FLIGHT)
        return SyncRunSummary(
            processed = claimed.size,
            acked = acked,
            failed = failed,
            dead = dead,
            stale = stale,
            conflicts = conflictsCount,
            recovered = recovered,
            remaining = remaining,
        )
    }

    /**
     * §11: on ACK the record's watermark advances at the SAME version.
     *
     * `_last_synced_content_hash` stores the DEVICE canonical identity of the
     * confirmed content — the cloud response hash is computed over the sync
     * payload (which carries protocol control keys), so it cannot serve as
     * the reference for local echo detection; the device's own hash definition
     * (canonical domain payload, sync fields excluded) is the stable identity
     * for the version==watermark comparison. The cloud hash stays observable
     * through the ACKED operation record itself.
     */
    private suspend fun markRecordSynced(op: SyncOperationRecord, cloudContentHash: String?) {
        val record = recordStore.get(op.recordId) ?: return
        if (record.version != op.version) return // a newer local version owns the next intent
        val deviceIdentity = CanonicalContentHash.hash(record.payload)
        recordStore.upsert(
            record.copy(
                syncState = SyncState.SYNCED,
                contentHash = deviceIdentity,
                lastSyncedVersion = op.version,
                lastSyncedOperationId = op.operationId.value,
                lastSyncedContentHash = deviceIdentity,
                updatedAt = clock(),
            ),
        )
        // [cloudContentHash] is deliberately not persisted onto the ACKED
        // operation: ACKED records are immutable history (§9.3). The cloud
        // identity remains observable on the cloud point itself and in the
        // STALE/CONFLICT/DUPLICATE responses that surface it.
        require(cloudContentHash == null || cloudContentHash.matches(Regex("[0-9a-f]{64}"))) {
            "acknowledged cloud content hash must be lowercase sha256 hex"
        }
    }

    private suspend fun recordPushConflict(op: SyncOperationRecord, outcome: SyncPushOutcome.Conflict) {
        val local = recordStore.get(op.recordId) ?: return
        conflicts.record(
            subject = local.subjectKey ?: "",
            local = local,
            incoming = ConflictEvidence(
                recordId = op.recordId.uuid,
                version = outcome.cloudVersion,
                contentHash = outcome.cloudContentHash,
                origin = "CLOUD",
                authority = null,
                title = null,
                content = null,
                tombstone = outcome.cloudTombstone == true,
            ),
            reason = "PUSH_CONFLICT",
        )
    }

    // ------------------------------------------------------------------
    // 12B.9 — cloud → local
    // ------------------------------------------------------------------

    override suspend fun pullAndApply(pageSize: Int): SyncPullSummary {
        require(pageSize in 1..MAX_PAGE) { "Page size must be within 1..$MAX_PAGE" }
        val cursor = readCursor()
        val batch = cloudKnowledge.pullKnowledge(cursor)

        var applied = 0
        var duplicates = 0
        var stale = 0
        var conflictCount = 0
        var tombstoned = 0
        var rejected = 0

        for (item in batch.items) {
            when (val outcome = applyItem(item)) {
                is CloudApplyOutcome.Applied ->
                    if (outcome.record.tombstone) tombstoned++ else applied++
                CloudApplyOutcome.Duplicate -> duplicates++
                CloudApplyOutcome.Stale -> stale++
                is CloudApplyOutcome.Conflict -> conflictCount++
                CloudApplyOutcome.RejectedInvalid -> rejected++
            }
        }

        // The cursor advances only AFTER the page was applied; a crash between
        // re-applies the page, which is idempotent by (id, version, hash).
        writeCursor(batch.nextCursor)

        return SyncPullSummary(
            applied = applied,
            duplicates = duplicates,
            stale = stale,
            conflicts = conflictCount,
            tombstoned = tombstoned,
            rejected = rejected,
            nextCursor = batch.nextCursor,
        )
    }

    /**
     * Phase 13.4 — apply ONE caller-supplied cloud item through the SAME
     * validation, classification, apply, conflict-recording and
     * resurrection-prevention pipeline as a pulled page. Used by the
     * Qdrant-native answer cache (explicit user action). The pull cursor is
     * deliberately untouched: this is not a pull.
     */
    suspend fun applyCloudItem(item: CloudKnowledgeItem): CloudApplyOutcome = applyItem(item)

    /** The single §12 per-item pipeline shared by pull pages and one-off items. */
    private suspend fun applyItem(item: CloudKnowledgeItem): CloudApplyOutcome {
        val local = validate(item)?.let { recordStore.get(it) }
        val candidate = item.toLocalRecordOrNull(local) ?: return CloudApplyOutcome.RejectedInvalid
        return when (SyncClassification.classify(local, candidate)) {
            SyncClassification.NEW, SyncClassification.UPDATE -> {
                recordStore.upsert(candidate)
                CloudApplyOutcome.Applied(candidate, update = local != null)
            }
            SyncClassification.DUPLICATE -> CloudApplyOutcome.Duplicate // no write (§13 echo)
            SyncClassification.STALE -> CloudApplyOutcome.Stale // local is ahead; cloud never overwrites
            SyncClassification.CONFLICT -> {
                // Record only; do NOT overwrite local state (12B.10 resolves).
                if (local == null) return CloudApplyOutcome.Stale
                val conflict = conflicts.record(
                    subject = item.subjectKey ?: local.subjectKey ?: "",
                    local = local,
                    incoming = ConflictEvidence(
                        recordId = item.memoryId,
                        version = item.version,
                        contentHash = item.contentHash,
                        origin = item.origin,
                        authority = item.authority,
                        title = item.title,
                        content = item.content,
                        tombstone = item.tombstone,
                    ),
                    reason = "PULL_CONFLICT",
                )
                CloudApplyOutcome.Conflict(conflict)
            }
        }
    }

    private fun validate(item: CloudKnowledgeItem): RecordId? =
        runCatching { RecordId.fromString(item.memoryId) }.getOrNull()?.takeIf {
            item.version >= 1 && item.contentHash.matches(CONTENT_HASH_RE)
        }

    /**
     * Map a cloud item into the local record shape (§12): written with
     * `_origin = CLOUD`, `_sync_state = SYNCED` and the watermark set, so the
     * change detector can never enqueue an echo operation for it (§13).
     * A local embedding (vector) is preserved for the same logical record:
     * retrieval quality is not a sync concern and vectors are excluded from
     * content hashing.
     */
    private suspend fun CloudKnowledgeItem.toLocalRecordOrNull(existing: Record?): Record? {
        val id = validate(this) ?: return null
        return Record(
            id = id,
            recordType = RecordType.MEMORY,
            entityId = null,
            // 13.4: preserve local vectors on update; freshly pulled items get
            // a real embedding via the optional hook (retrieval parity). An
            // embedding failure degrades the record to payload-only (keyword
            // retrieval still covers it) instead of failing the sync itself.
            vector = existing?.vector ?: runCatching { cloudEmbedding?.invoke(this) }.getOrNull(),
            payload = mapOf(
                "title" to JsonValue.fromString(title),
                "content" to JsonValue.fromString(content),
            ),
            version = version,
            createdAt = existing?.createdAt ?: updatedAt,
            updatedAt = updatedAt,
            source = "CLOUD",
            syncState = SyncState.SYNCED,
            syncDecision = SyncDecision.SYNC,
            subjectKey = subjectKey,
            contentHash = contentHash,
            supersedes = supersedes,
            tombstone = tombstone,
            origin = RecordOrigin.CLOUD,
            authority = authority,
            metadata = metadata,
            lastSyncedVersion = version,
            lastSyncedContentHash = contentHash,
        )
    }

    // ------------------------------------------------------------------
    // sys_cursor (payload-only Qdrant point — VERIFIED possible by 12B.4)
    // ------------------------------------------------------------------

    private suspend fun readCursor(): String? {
        val record = recordStore.get(CURSOR_ID) ?: return null
        return (record.payload[CURSOR_FIELD] as? JsonString)?.value?.takeIf { it.isNotEmpty() }
    }

    private suspend fun writeCursor(next: String?) {
        val existing = recordStore.get(CURSOR_ID)
        recordStore.upsert(
            Record(
                id = CURSOR_ID,
                recordType = RecordType.SYS_CURSOR,
                entityId = null,
                vector = null,
                payload = mapOf(
                    CURSOR_FIELD to (next?.let { JsonValue.fromString(it) } ?: JsonString("")),
                ),
                version = (existing?.version ?: 0) + 1,
                createdAt = existing?.createdAt ?: clock(),
                updatedAt = clock(),
                // Cursor state is pure local system state.
                syncDecision = SyncDecision.LOCAL_ONLY,
            ),
        )
    }

    private companion object {
        const val MAX_PAGE = 200
        const val CURSOR_FIELD = "cursor"
        val CONTENT_HASH_RE = Regex("[0-9a-f]{64}")

        /** Deterministic point id of the knowledge pull cursor. */
        val CURSOR_ID: RecordId = RecordId.fromString(
            java.util.UUID.nameUUIDFromBytes("edgemind:cursor:knowledge".toByteArray(Charsets.UTF_8))
                .toString(),
        )
    }
}
