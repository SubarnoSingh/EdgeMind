package com.example.EdgeMemo.data.local.sync

import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.LocalRecordStore
import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordFilter
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordOrigin
import com.example.EdgeMemo.core.record.RecordQuery
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.record.SyncState
import com.example.EdgeMemo.core.sync.ChangeDetectionOutcome
import com.example.EdgeMemo.core.sync.SyncOperationId
import com.example.EdgeMemo.domain.conflict.ConflictResolutionState

/**
 * Outcome of one resolution call.
 *
 * [appliedVersion] is the record version the resolution produced (null when
 * nothing was written: keep-local leaves the record untouched, dismiss keeps
 * both sides, and an already-resolved conflict performs no further work).
 */
data class ConflictResolution(
    val conflict: Record,
    val case: ConflictCase,
    val state: ConflictResolutionState,
    val appliedVersion: Int?,
    val followUpOperationId: SyncOperationId?,
    val alreadyResolved: Boolean,
)

/**
 * Phase 12B.10 — conflict resolution over the durable evidence 12B.9 records.
 *
 * Strategy is the one the Phase 12 architecture defines (§16):
 * **authority priority, then explicit conflict record for the remainder.**
 * Cloud authoritative knowledge wins; genuinely divergent local knowledge is
 * preserved as a conflict record rather than discarded. Reuses the VERIFIED
 * legacy `DefaultConflictResolver` semantics — keep-local (record untouched),
 * keep-cloud (incoming content becomes the active state; divergent local
 * content survives inside the conflict evidence), dismiss (both sides kept).
 * There is deliberately no MERGE (same legacy decision).
 *
 * Invariants:
 *  - Evidence is never deleted and never overwritten after resolution: the
 *    SAME deterministic conflict point carries the full evidence plus the
 *    outcome (`state`, `resolution`, `resolved_at`). A resolved conflict is
 *    immutable.
 *  - Resolution is crash-safe without transactions (qdrant-edge has none):
 *    a durable intent marker (`resolution_choice`, `resolution_version`) is
 *    written on the conflict point FIRST; the record change and deterministic
 *    follow-up operation are idempotent replays derived from that frozen
 *    intent, so a crash mid-resolution can never double-bump a version or
 *    create two operations for one decision.
 *  - The resolution version is computed from the FROZEN evidence —
 *    `max(local_version, incoming_version) + 1` — never from live record
 *    state, so replay is deterministic. If the live record already advanced
 *    beyond that version, a newer local write superseded the conflict and the
 *    resolution converges without resurrecting older content.
 *  - Follow-up operations use the frozen §9 identity `TYPE:<record_uuid>:<version>`
 *    through the 12B.7 detector, which enforces policy gates (LOCAL_ONLY,
 *    redaction) and idempotency. Cloud converges via version ordering —
 *    no endless conflict→operation→conflict cycle: once the newer version is
 *    ACKed, re-pull classifies DUPLICATE/STALE, never CONFLICT again.
 *  - Tombstone rules (§14, §23.1 rows 6–9): an automatic authority resolution
 *    NEVER resurrects a local tombstone with older cloud state; resurrection
 *    requires the explicit keep-cloud decision at a strictly newer version.
 *    A stale delete (older tombstone) can never destroy newer data: it is
 *    either STALE (pull path) or a conflict frozen at lower evidence versions,
 *    whose resolution always lands at max+1.
 */
class QdrantConflictResolver(
    private val recordStore: LocalRecordStore,
    private val detector: QdrantChangeDetector,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Deterministic automatic pass (§16 authority priority). */
    suspend fun resolveByAuthority(limit: Int = 50): List<ConflictResolution> {
        val resolved = mutableListOf<ConflictResolution>()
        for (conflict in listUnresolved(limit)) {
            val case = QdrantConflictRecorder.caseOf(conflict)
            // Authority priority: cloud authority wins only when the local
            // side has no competing authority claim…
            val authorityWins = case.incomingAuthority != null && case.localAuthority == null
            // …and an automatic pass NEVER resurrects a local tombstone with
            // older active cloud content (§23.1 row 8-9: resurrection is
            // explicit-only).
            val refusesResurrection = case.localTombstone && !case.incomingTombstone
            if (authorityWins && !refusesResurrection) {
                resolved += resolveCloud(
                    case.conflictId,
                    note = "automatic:authority(${case.incomingAuthority})",
                )
            }
        }
        return resolved
    }

    /** Explicit keep-local: the local record stands untouched (§16 remainder). */
    suspend fun resolveLocal(conflictId: RecordId, note: String? = null): ConflictResolution =
        finalize(
            conflictId = conflictId,
            choice = ConflictResolutionState.RESOLVED_LOCAL,
            note = note ?: "kept local",
            applyRecord = null,
        )

    /**
     * Explicit keep-cloud: the incoming content becomes the active local state
     * at the frozen resolution version, with a deterministic follow-up
     * operation so the cloud converges. This is the ONLY resurrection path
     * (a local tombstone may be explicitly superseded at a newer version).
     */
    suspend fun resolveCloud(conflictId: RecordId, note: String? = null): ConflictResolution =
        finalize(
            conflictId = conflictId,
            choice = ConflictResolutionState.RESOLVED_CLOUD,
            note = note ?: "accepted cloud",
            applyRecord = true,
        )

    /** Dismiss: divergence acknowledged, both sides stay as they are. */
    suspend fun dismiss(conflictId: RecordId, note: String? = null): ConflictResolution =
        finalize(
            conflictId = conflictId,
            choice = ConflictResolutionState.DISMISSED,
            note = note ?: "kept both, reviewed",
            applyRecord = null,
        )

    /** Page-bounded scan over the indexed `record_type=conflict` partition. */
    suspend fun listUnresolved(limit: Int): List<Record> {
        require(limit > 0)
        val page = recordStore.scroll(
            RecordQuery.Scroll(
                limit = limit.coerceAtMost(MAX_SCAN),
                recordTypes = setOf(RecordType.CONFLICT),
            ),
        )
        return page.records.filter { QdrantConflictRecorder.caseOf(it).isUnresolved }
    }

    suspend fun find(conflictId: RecordId): Record? = recordStore.get(conflictId)

    // ------------------------------------------------------------------
    // crash-safe resolution protocol (ordered writes, not transactions)
    // ------------------------------------------------------------------

    private suspend fun finalize(
        conflictId: RecordId,
        choice: ConflictResolutionState,
        note: String,
        applyRecord: Boolean?,
    ): ConflictResolution {
        require(
            choice == ConflictResolutionState.RESOLVED_LOCAL ||
                choice == ConflictResolutionState.RESOLVED_CLOUD ||
                choice == ConflictResolutionState.DISMISSED,
        ) { "Unsupported conflict resolution choice: $choice" }

        val conflict = recordStore.get(conflictId)
            ?: throw IllegalArgumentException("Conflict not found: $conflictId")
        val case = QdrantConflictRecorder.caseOf(conflict)

        // A resolved conflict is immutable history — repeated calls are a
        // pure no-op read (idempotent, requirement 8).
        if (!case.isUnresolved) {
            return ConflictResolution(
                conflict = conflict,
                case = case,
                state = ConflictResolutionState.valueOf(case.state),
                appliedVersion = case.appliedVersion,
                followUpOperationId = case.followUpOperationId?.let(SyncOperationId::parse),
                alreadyResolved = true,
            )
        }

        // 1 — durable intent marker. The version is derived from the FROZEN
        // evidence, so every replay of this decision targets the same version.
        val resolutionVersion = case.resolutionVersion
            ?: maxOf(case.localVersion, case.incomingVersion) + 1
        val intent = case.pendingChoice?.let {
            runCatching { ConflictResolutionState.valueOf(it) }.getOrNull()
        }
        // A half-applied attempt with a DIFFERENT choice would corrupt the
        // deterministic version; refuse instead of silently switching sides.
        if (intent != null && intent != choice && case.appliedVersion != null) {
            throw IllegalStateException(
                "Conflict $conflictId is mid-resolution as $intent; " +
                    "cannot switch to $choice after the record change was applied",
            )
        }
        if (intent != choice || case.resolutionVersion == null) {
            writeConflictState(
                conflict,
                state = ConflictResolutionState.UNRESOLVED,
                note = null,
                resolvedAt = null,
                choice = choice,
                version = resolutionVersion,
                appliedVersion = case.appliedVersion,
                followUp = case.followUpOperationId,
            )
        }

        // 2 — record change + deterministic follow-up operation (both are
        // idempotent replays for RESOLVED_CLOUD).
        var applied: Int? = null
        var followUp: String? = case.followUpOperationId
        var supersededByLocalVersion: Int? = null
        if (choice == ConflictResolutionState.RESOLVED_CLOUD && applyRecord == true) {
            val local = recordStore.get(case.localRecordId)
            when {
                local == null -> {
                    // Local record already gone; nothing to overwrite. The
                    // decision is still durable history of the divergence.
                }
                // CRASH REPLAY continuation: the applied write happened in a
                // previous attempt (same deterministic version AND the same
                // frozen incoming content hash) but the applied marker was
                // lost. Resume: do NOT re-bump; just mark applied and enqueue
                // the idempotent follow-up operation.
                local.version == resolutionVersion &&
                    local.contentHash == case.incomingContentHash -> {
                    applied = resolutionVersion
                    writeConflictState(
                        conflict = recordStore.get(conflictId)!!,
                        state = ConflictResolutionState.UNRESOLVED,
                        note = null,
                        resolvedAt = null,
                        choice = choice,
                        version = resolutionVersion,
                        appliedVersion = resolutionVersion,
                        followUp = followUp,
                    )
                    followUp = enqueueFollowUp(case.localRecordId) ?: followUp
                }
                local.version >= resolutionVersion -> {
                    // A NEWER local write superseded this conflict. Applying
                    // frozen cloud content now would move the record backwards
                    // in version ordering — forbidden monotonicity. Converge
                    // without resurrecting older content; noted in evidence.
                    supersededByLocalVersion = local.version
                }
                else -> {
                    val tombstone = case.incomingTombstone
                    if (!tombstone && case.incomingTitle.isEmpty() && case.incomingContent.isEmpty()) {
                        // Push-time CONFLICT responses carry no cloud content:
                        // keep-cloud cannot apply what the cloud never sent.
                        // Honest refusal, never a fabricated overwrite.
                        throw IllegalStateException(
                            "Conflict $conflictId has no incoming content evidence; " +
                                "choose keep-local or dismiss instead",
                        )
                    }
                    val appliedRecord = local.copy(
                        vector = local.vector, // embeddings are retrieval quality, not sync state
                        payload = linkedMapOf<String, JsonValue>().apply {
                            putAll(local.payload)
                            remove("title")
                            remove("content")
                            if (!tombstone) {
                                put("title", JsonValue.fromString(case.incomingTitle))
                                put("content", JsonValue.fromString(case.incomingContent))
                            } else {
                                // A tombstone keeps the last known content in
                                // evidence; the applied state carries none new.
                            }
                        },
                        version = resolutionVersion,
                        updatedAt = clock(),
                        contentHash = case.incomingContentHash,
                        tombstone = tombstone,
                        deletedAt = if (tombstone) clock() else null,
                        origin = RecordOrigin.LOCAL,
                        source = "CONFLICT_RESOLUTION",
                        authority = case.incomingAuthority ?: local.authority,
                        // Watermark untouched on purpose: the resolved content
                        // is NOT confirmed on the cloud until the follow-up
                        // operation is ACKed.
                    )
                    recordStore.upsert(appliedRecord)
                    applied = resolutionVersion
                    // 3 — mark the applied version durably BEFORE enqueuing
                    // the operation: replay after a crash knows the record
                    // write happened and must not re-bump.
                    writeConflictState(
                        conflict = recordStore.get(conflictId)!!,
                        state = ConflictResolutionState.UNRESOLVED,
                        note = null,
                        resolvedAt = null,
                        choice = choice,
                        version = resolutionVersion,
                        appliedVersion = resolutionVersion,
                        followUp = followUp,
                    )
                    // 4 — deterministic follow-up operation through the 12B.7
                    // detector (policy gates + idempotent identity included).
                    followUp = enqueueFollowUp(case.localRecordId) ?: followUp
                }
            }
        }

        // 5 — flip the conflict to its terminal resolved state (evidence kept).
        val resolutionNote = when {
            supersededByLocalVersion != null ->
                "$note (superseded by newer local v$supersededByLocalVersion; older cloud content not applied)"
            else -> note
        }
        val finalConflict = writeConflictState(
            conflict = recordStore.get(conflictId)!!,
            state = choice,
            note = resolutionNote,
            resolvedAt = clock(),
            choice = choice,
            version = resolutionVersion,
            appliedVersion = applied ?: case.appliedVersion,
            followUp = followUp,
        )
        return ConflictResolution(
            conflict = finalConflict,
            case = QdrantConflictRecorder.caseOf(finalConflict),
            state = choice,
            appliedVersion = applied ?: case.appliedVersion,
            followUpOperationId = followUp?.let(SyncOperationId::parse),
            alreadyResolved = false,
        )
    }

    /**
     * Enqueue the deterministic follow-up operation for the applied record
     * through the 12B.7 detector (policy gates and idempotent identity
     * included). Returns the operation identity, or null when the detector
     * legitimately produced none (LOCAL_ONLY / redaction gap).
     */
    private suspend fun enqueueFollowUp(recordId: RecordId): String? =
        when (val outcome = detector.detectAndEnqueue(recordStore.get(recordId)!!)) {
            is ChangeDetectionOutcome.Enqueued -> outcome.operation.operationId.value
            is ChangeDetectionOutcome.AlreadyEnqueued -> outcome.operation.operationId.value
            else -> null
        }

    private suspend fun writeConflictState(
        conflict: Record,
        state: ConflictResolutionState,
        note: String?,
        resolvedAt: Long?,
        choice: ConflictResolutionState,
        version: Int,
        appliedVersion: Int?,
        followUp: String?,
    ): Record {
        val payload = LinkedHashMap(conflict.payload)
        payload[QdrantConflictRecorder.FIELD_STATE] = JsonValue.fromString(state.name)
        payload[QdrantConflictRecorder.FIELD_RESOLUTION_CHOICE] = JsonValue.fromString(choice.name)
        payload[QdrantConflictRecorder.FIELD_RESOLUTION_VERSION] = JsonValue.fromInt(version)
        appliedVersion?.let {
            payload[QdrantConflictRecorder.FIELD_RESOLUTION_APPLIED] = JsonValue.fromInt(it)
        }
        followUp?.let {
            payload[QdrantConflictRecorder.FIELD_FOLLOW_UP_OPERATION_ID] = JsonValue.fromString(it)
        }
        note?.let { payload[QdrantConflictRecorder.FIELD_RESOLUTION] = JsonValue.fromString(it) }
        resolvedAt?.let { payload[QdrantConflictRecorder.FIELD_RESOLVED_AT] = JsonValue.fromLong(it) }
        // Keep the recorded `reason` stable; refresh evidence-only fields that
        // the recorder wrote; everything else is preserved untouched.
        check(payload.keys.containsAll(BASE_EVIDENCE_KEYS)) {
            "Conflict evidence must never lose fields during resolution"
        }
        return recordStore.upsert(
            conflict.copy(
                payload = payload,
                version = conflict.version + 1,
                updatedAt = clock(),
            ),
        )
    }

    private companion object {
        const val MAX_SCAN = 200
        val BASE_EVIDENCE_KEYS = setOf(
            QdrantConflictRecorder.FIELD_SUBJECT,
            QdrantConflictRecorder.FIELD_LOCAL_RECORD_ID,
            QdrantConflictRecorder.FIELD_INCOMING_RECORD_ID,
            QdrantConflictRecorder.FIELD_LOCAL_VERSION,
            QdrantConflictRecorder.FIELD_INCOMING_VERSION,
            QdrantConflictRecorder.FIELD_LOCAL_CONTENT_HASH,
            QdrantConflictRecorder.FIELD_INCOMING_CONTENT_HASH,
            QdrantConflictRecorder.FIELD_LOCAL_ORIGIN,
            QdrantConflictRecorder.FIELD_INCOMING_ORIGIN,
            QdrantConflictRecorder.FIELD_LOCAL_AUTHORITY,
            QdrantConflictRecorder.FIELD_INCOMING_AUTHORITY,
            QdrantConflictRecorder.FIELD_LOCAL_TOMBSTONE,
            QdrantConflictRecorder.FIELD_INCOMING_TOMBSTONE,
            QdrantConflictRecorder.FIELD_LOCAL_TITLE,
            QdrantConflictRecorder.FIELD_LOCAL_CONTENT,
            QdrantConflictRecorder.FIELD_INCOMING_TITLE,
            QdrantConflictRecorder.FIELD_INCOMING_CONTENT,
            QdrantConflictRecorder.FIELD_DETECTED_AT,
            QdrantConflictRecorder.FIELD_REASON,
        )
    }
}
