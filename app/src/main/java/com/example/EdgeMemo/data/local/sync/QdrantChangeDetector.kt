package com.example.EdgeMemo.data.local.sync

import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.LocalRecordStore
import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordOrigin
import com.example.EdgeMemo.core.record.SyncDecision
import com.example.EdgeMemo.core.record.SyncState
import com.example.EdgeMemo.core.sync.CanonicalContentHash
import com.example.EdgeMemo.core.sync.ChangeDetectionOutcome
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.core.sync.SyncClassification
import com.example.EdgeMemo.core.sync.SyncOperationId
import com.example.EdgeMemo.core.sync.SyncOperationRecord
import com.example.EdgeMemo.core.sync.SyncOperationStore
import com.example.EdgeMemo.core.sync.SyncOperationType

/**
 * Phase 12B.7 — Qdrant-native change detection.
 *
 * Compares one locally written record against its §7.3 synchronization
 * watermark (stored in the record's own envelope in Qdrant) and, when the
 * change is real and policy permits it, feeds the Qdrant-native operation
 * store with a deterministic, version-scoped operation identity. Nothing is
 * sent to the cloud here; that is 12B.8.
 *
 * Rules (all inputs are Qdrant-resident; no Room, no second persistence):
 *  - LOCAL_ONLY never produces an operation and WITHDRAWS still-claimable
 *    operations for the record (§10.4 / §26).
 *  - cloud-origin records never produce outgoing operations (§13 echo guard,
 *    generalizing the VERIFIED `SyncPayloadFactory` CLOUD→null rule).
 *  - a version above the watermark enqueues `UPSERT:<id>:<version>`
 *    (or `TOMBSTONE:...` for tombstoned writes); the same version below the
 *    watermark is STALE and is never enqueued.
 *  - integer versions are ordering metadata, never causality: a same-version
 *    write whose canonical content hash disagrees with the record's own stored
 *    content hash is a CONFLICT and is never silently enqueued or pushed.
 *  - detection is idempotent: the operation identity is deterministic, so a
 *    repeated detection returns the stored operation without mutating it.
 */
class QdrantChangeDetector(
    private val recordStore: LocalRecordStore,
    private val operationStore: SyncOperationStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Run detection for [record], which must already be written to the local
     * store at its current version.
     */
    suspend fun detectAndEnqueue(record: Record): ChangeDetectionOutcome {
        require(recordStore.isOpen) { "Record store must be open for change detection" }

        // Policy gate FIRST: LOCAL_ONLY content must never create an
        // operation, and any still-claimable operations for the record are
        // withdrawn (§10.4).
        if (record.syncDecision == SyncDecision.LOCAL_ONLY) {
            withdrawClaimableOperations(record.id)
            return ChangeDetectionOutcome.LocalOnlyProtected
        }

        // Echo guard: cloud-origin records are never pushed back (§13).
        if (record.origin == RecordOrigin.CLOUD) {
            return ChangeDetectionOutcome.CloudOriginIgnored
        }

        // §7.1 matrix against the CLOUD-CONFIRMED baseline (§7.3 watermark).
        // Versions are ordering metadata; the canonical content hash —
        // computed here from the payload itself, sync fields excluded — is
        // the identity claim at the confirmed version.
        val canonicalHash = CanonicalContentHash.hash(record.payload)
        val watermark = record.lastSyncedVersion ?: 0
        when {
            record.version < watermark ->
                return ChangeDetectionOutcome.NoChange(SyncClassification.STALE)
            record.version == watermark -> {
                val confirmedHash = record.lastSyncedContentHash
                return ChangeDetectionOutcome.NoChange(
                    if (confirmedHash == null || confirmedHash == canonicalHash) {
                        SyncClassification.DUPLICATE
                    } else {
                        // Same version, different content: divergence. Never
                        // auto-won, never silently enqueued.
                        SyncClassification.CONFLICT
                    },
                )
            }
            else -> Unit // record.version > watermark → genuine NEW/UPDATE
        }

        val operationType =
            if (record.tombstone) SyncOperationType.TOMBSTONE else SyncOperationType.UPSERT
        val operationId =
            SyncOperationId.generate(operationType, record.id, record.version)

        // Deterministic identity → idempotent detection: if this (record,
        // version, type) intent already exists it is returned unchanged.
        operationStore.findByOperationId(operationId)?.let { existing ->
            return ChangeDetectionOutcome.AlreadyEnqueued(existing)
        }

        val domainPayload = syncPayloadFor(record)
            ?: return ChangeDetectionOutcome.RedactionUnavailable

        val instant = clock()
        val operation = SyncOperationRecord(
            recordId = record.id,
            operationId = operationId,
            operationType = operationType,
            state = OutboxOperationState.PENDING,
            attempts = 0,
            lastError = null,
            createdAt = instant,
            updatedAt = instant,
            leaseUntil = null,
            version = record.version,
            syncDecision = record.syncDecision,
            redacted = record.syncDecision == SyncDecision.SYNC_REDACTED,
            payload = domainPayload,
        )

        val stored = operationStore.enqueue(operation)
        // §11: record _sync_state = PENDING. This is a state-only write at the
        // same version; sync metadata is excluded from the canonical hash, so
        // marking the record pending can never change its content identity.
        markRecordPending(record)
        return if (stored.state == OutboxOperationState.PENDING && stored == operation) {
            ChangeDetectionOutcome.Enqueued(stored)
        } else {
            ChangeDetectionOutcome.AlreadyEnqueued(stored)
        }
    }

    /**
     * Delete operations for [recordId] that are still claimable
     * (PENDING/FAILED). IN_FLIGHT operations are left alone: they may already
     * have been delivered, and their ack path is idempotent. ACKED/DEAD are
     * immutable history.
     */
    suspend fun withdrawClaimableOperations(recordId: RecordId) {
        val claimable = buildList {
            var offset: String? = null
            while (true) {
                val page = operationStore.listByStates(
                    states = setOf(OutboxOperationState.PENDING, OutboxOperationState.FAILED),
                    limit = PAGE_SIZE,
                    offsetId = offset,
                )
                page.operations.filter { it.recordId == recordId }.forEach { add(it) }
                if (page.nextOffsetId == null || page.operations.isEmpty()) break
                offset = page.nextOffsetId
            }
        }
        for (op in claimable) {
            recordStore.delete(QdrantSyncOperationStore.pointId(op.operationId))
        }
    }

    private suspend fun markRecordPending(record: Record) {
        val canonical = CanonicalContentHash.hash(record.payload)
        // Stamp the authoritative content identity and the §11 PENDING state.
        // Both are sync metadata: neither participates in the canonical hash,
        // so this state-only write at the same version can never echo.
        if (record.syncState == SyncState.PENDING && record.contentHash == canonical) return
        recordStore.upsert(
            record.copy(
                syncState = SyncState.PENDING,
                contentHash = canonical,
                updatedAt = clock(),
            ),
        )
    }

    /**
     * The policy-sanctioned domain payload of the outgoing operation — the
     * only representation that may ever leave the device (§20.2, mirroring
     * the VERIFIED `SyncPayloadFactory` role for the legacy path).
     *
     * Returns null when a `SYNC_REDACTED` record has no stored redacted
     * representation; raw content is never substituted.
     */
    private fun syncPayloadFor(record: Record): Map<String, JsonValue>? {
        val base = linkedMapOf<String, JsonValue>()
        when (record.syncDecision) {
            SyncDecision.LOCAL_ONLY -> return null
            SyncDecision.SYNC -> {
                // The whole domain payload is sanctioned.
                base.putAll(record.payload)
            }
            SyncDecision.SYNC_REDACTED -> {
                val title = record.redactedTitle ?: return null
                val content = record.redactedContent ?: return null
                base["title"] = JsonValue.fromString(title)
                base["content"] = JsonValue.fromString(content)
                // Non-sanctioned domain fields are dropped entirely.
                if (record.tags.isNotEmpty()) {
                    base["tags"] = JsonValue.fromList(record.tags.map { JsonValue.fromString(it) })
                }
                if (record.metadata.isNotEmpty()) {
                    base["metadata"] = JsonValue.fromMap(
                        record.metadata.mapValues { JsonValue.fromString(it.value) },
                    )
                }
            }
        }
        // Frozen §23 envelope control keys expected by the backend contract.
        base["type"] = JsonValue.fromString(record.recordType.name)
        base["source"] = JsonValue.fromString(record.source)
        base["origin"] = JsonValue.fromString("LOCAL")
        record.subjectKey?.let { base["subjectKey"] = JsonValue.fromString(it) }
        record.supersedes?.let { base["supersedes"] = JsonValue.fromString(it) }
        if (record.tombstone) {
            base["tombstone"] = JsonValue.fromBoolean(true)
            record.deletedAt?.let { base["deletedAt"] = JsonValue.fromLong(it) }
        } else {
            base["tombstone"] = JsonValue.fromBoolean(false)
        }
        return base
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}
