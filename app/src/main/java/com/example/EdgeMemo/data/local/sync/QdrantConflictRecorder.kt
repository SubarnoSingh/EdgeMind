package com.example.EdgeMemo.data.local.sync

import com.example.EdgeMemo.core.record.JsonString
import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.LocalRecordStore
import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.record.SyncDecision
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * One side's evidence in a detected divergence. Kept as a plain value so the
 * recorder works identically for cloud-pull conflicts (§12) and push-time
 * CONFLICT responses (§23.1 row 3) without leaking either model into the
 * other.
 */
data class ConflictEvidence(
    val recordId: String,
    val version: Int,
    val contentHash: String,
    val origin: String,
    val authority: String?,
    val title: String?,
    val content: String?,
    val tombstone: Boolean = false,
)

/**
 * Qdrant-native conflict RECORDING (Phase 12 §15.2). Resolution strategy is
 * deliberately NOT implemented here — that is 12B.10.
 *
 * A conflict is stored as a payload-only `record_type=conflict` point in the
 * same shard, carrying BOTH sides' evidence. The point id is derived
 * deterministically from
 * `SHA-256(subject | local_id | local_hash | incoming_id | incoming_hash)` —
 * the identity convention VERIFIED in the legacy `CloudConflictRecorder` —
 * so repeated detection of the same contradictory pair re-records onto the
 * same point and can never duplicate or resurrect evidence.
 *
 * The conflict record is LOCAL_ONLY: a local diagnostic artifact that never
 * enters the outbox or leaves the device (§26).
 */
class QdrantConflictRecorder(
    private val recordStore: LocalRecordStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Record (or refresh, while still UNRESOLVED) the divergence between
     * [local] and [incoming]. Returns the stored conflict record.
     */
    suspend fun record(
        subject: String,
        local: Record,
        incoming: ConflictEvidence,
        reason: String,
    ): Record {
        val id = conflictPointId(subject, local.id.uuid, local.contentHash.orEmpty(), incoming.recordId, incoming.contentHash)
        val existing = recordStore.get(id)
        // A resolved conflict is history; never overwrite its outcome.
        if (existing != null && existing.payload["state"] != STATE_UNRESOLVED) {
            return existing
        }
        val createdAt = existing?.payload?.get("detected_at") ?: JsonValue.fromLong(clock())
        val entity = Record(
            id = id,
            recordType = RecordType.CONFLICT,
            entityId = null,
            vector = null,
            payload = mapOf(
                "subject" to JsonValue.fromString(subject),
                "local_record_id" to JsonValue.fromString(local.id.uuid),
                "incoming_record_id" to JsonValue.fromString(incoming.recordId),
                "local_version" to JsonValue.fromInt(local.version),
                "incoming_version" to JsonValue.fromInt(incoming.version),
                "local_content_hash" to JsonValue.fromString(local.contentHash.orEmpty()),
                "incoming_content_hash" to JsonValue.fromString(incoming.contentHash),
                "local_origin" to JsonValue.fromString(local.origin.name),
                "incoming_origin" to JsonValue.fromString(incoming.origin),
                "local_authority" to JsonValue.fromString(local.authority.orEmpty()),
                "incoming_authority" to JsonValue.fromString(incoming.authority.orEmpty()),
                "local_tombstone" to JsonValue.fromBoolean(local.tombstone),
                "incoming_tombstone" to JsonValue.fromBoolean(incoming.tombstone),
                "local_title" to JsonValue.fromString(local.payload["title"]?.getStringOrNull().orEmpty()),
                "local_content" to JsonValue.fromString(local.payload["content"]?.getStringOrNull().orEmpty()),
                "incoming_title" to JsonValue.fromString(incoming.title.orEmpty()),
                "incoming_content" to JsonValue.fromString(incoming.content.orEmpty()),
                "detected_at" to createdAt,
                "reason" to JsonValue.fromString(reason),
                "state" to STATE_UNRESOLVED,
            ),
            version = existing?.version ?: 1,
            createdAt = createdAt.asLongOrNow(),
            updatedAt = clock(),
            syncDecision = SyncDecision.LOCAL_ONLY,
        )
        return recordStore.upsert(entity)
    }

    companion object {
        val STATE_UNRESOLVED: JsonValue = JsonValue.fromString("UNRESOLVED")

        const val FIELD_STATE = "state"
        const val FIELD_REASON = "reason"
        const val FIELD_SUBJECT = "subject"
        const val FIELD_LOCAL_RECORD_ID = "local_record_id"
        const val FIELD_INCOMING_RECORD_ID = "incoming_record_id"
        const val FIELD_LOCAL_VERSION = "local_version"
        const val FIELD_INCOMING_VERSION = "incoming_version"
        const val FIELD_LOCAL_CONTENT_HASH = "local_content_hash"
        const val FIELD_INCOMING_CONTENT_HASH = "incoming_content_hash"
        const val FIELD_LOCAL_ORIGIN = "local_origin"
        const val FIELD_INCOMING_ORIGIN = "incoming_origin"
        const val FIELD_LOCAL_AUTHORITY = "local_authority"
        const val FIELD_INCOMING_AUTHORITY = "incoming_authority"
        const val FIELD_LOCAL_TOMBSTONE = "local_tombstone"
        const val FIELD_INCOMING_TOMBSTONE = "incoming_tombstone"
        const val FIELD_LOCAL_TITLE = "local_title"
        const val FIELD_LOCAL_CONTENT = "local_content"
        const val FIELD_INCOMING_TITLE = "incoming_title"
        const val FIELD_INCOMING_CONTENT = "incoming_content"
        const val FIELD_DETECTED_AT = "detected_at"
        const val FIELD_RESOLUTION = "resolution"
        const val FIELD_RESOLVED_AT = "resolved_at"
        const val FIELD_RESOLUTION_CHOICE = "resolution_choice"
        const val FIELD_RESOLUTION_VERSION = "resolution_version"
        const val FIELD_RESOLUTION_APPLIED = "resolution_applied_version"
        const val FIELD_FOLLOW_UP_OPERATION_ID = "follow_up_operation_id"

        /** Parse the frozen evidence schema from a stored conflict point. */
        @JvmStatic
        fun caseOf(record: Record): ConflictCase {
            require(record.recordType == RecordType.CONFLICT) {
                "Not a conflict record: ${record.recordType}"
            }
            val p = record.payload
            return ConflictCase(
                conflictId = record.id,
                subject = p.stringOf(FIELD_SUBJECT),
                localRecordId = RecordId.fromString(p.stringOf(FIELD_LOCAL_RECORD_ID)),
                incomingRecordId = p.stringOf(FIELD_INCOMING_RECORD_ID),
                localVersion = p.intOf(FIELD_LOCAL_VERSION),
                incomingVersion = p.intOf(FIELD_INCOMING_VERSION),
                localContentHash = p.stringOf(FIELD_LOCAL_CONTENT_HASH),
                incomingContentHash = p.stringOf(FIELD_INCOMING_CONTENT_HASH),
                localOrigin = p.stringOf(FIELD_LOCAL_ORIGIN),
                incomingOrigin = p.stringOf(FIELD_INCOMING_ORIGIN),
                localAuthority = p.stringOf(FIELD_LOCAL_AUTHORITY).takeIf { it.isNotEmpty() },
                incomingAuthority = p.stringOf(FIELD_INCOMING_AUTHORITY).takeIf { it.isNotEmpty() },
                localTombstone = p.booleanOf(FIELD_LOCAL_TOMBSTONE),
                incomingTombstone = p.booleanOf(FIELD_INCOMING_TOMBSTONE),
                localTitle = p.stringOf(FIELD_LOCAL_TITLE),
                localContent = p.stringOf(FIELD_LOCAL_CONTENT),
                incomingTitle = p.stringOf(FIELD_INCOMING_TITLE),
                incomingContent = p.stringOf(FIELD_INCOMING_CONTENT),
                detectedAt = p.longOf(FIELD_DETECTED_AT),
                reason = p.stringOf(FIELD_REASON),
                state = p.stringOf(FIELD_STATE),
                resolution = p.stringOf(FIELD_RESOLUTION).takeIf { it.isNotEmpty() },
                resolvedAt = p.longOfOrNull(FIELD_RESOLVED_AT),
                pendingChoice = p.stringOf(FIELD_RESOLUTION_CHOICE).takeIf { it.isNotEmpty() },
                resolutionVersion = p.intOfOrNull(FIELD_RESOLUTION_VERSION),
                appliedVersion = p.intOfOrNull(FIELD_RESOLUTION_APPLIED),
                followUpOperationId = p.stringOf(FIELD_FOLLOW_UP_OPERATION_ID).takeIf { it.isNotEmpty() },
            )
        }

        /** Deterministic UUID derived from the frozen conflict-key convention. */
        @JvmStatic
        fun conflictPointId(
            subject: String,
            localRecordId: String,
            localContentHash: String,
            incomingRecordId: String,
            incomingContentHash: String,
        ): RecordId {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(
                    "conflict|$subject|$localRecordId|$localContentHash|$incomingRecordId|$incomingContentHash"
                        .toByteArray(StandardCharsets.UTF_8),
                )
                .joinToString("") { "%02x".format(it) }
            return RecordId.fromString(
                UUID.nameUUIDFromBytes("edgemind:conflict:$digest".toByteArray(StandardCharsets.UTF_8))
                    .toString(),
            )
        }
    }
}

private fun JsonValue?.getStringOrNull(): String? = (this as? JsonString)?.value

private fun JsonValue.asLongOrNow(): Long =
    (this as? com.example.EdgeMemo.core.record.JsonNumber)?.value?.toLong() ?: System.currentTimeMillis()

/** Frozen evidence view of one stored conflict point (12B.9 schema). */
data class ConflictCase(
    val conflictId: RecordId,
    val subject: String,
    val localRecordId: RecordId,
    val incomingRecordId: String,
    val localVersion: Int,
    val incomingVersion: Int,
    val localContentHash: String,
    val incomingContentHash: String,
    val localOrigin: String,
    val incomingOrigin: String,
    val localAuthority: String?,
    val incomingAuthority: String?,
    val localTombstone: Boolean,
    val incomingTombstone: Boolean,
    val localTitle: String,
    val localContent: String,
    val incomingTitle: String,
    val incomingContent: String,
    val detectedAt: Long,
    val reason: String,
    val state: String,
    val resolution: String?,
    val resolvedAt: Long?,
    val pendingChoice: String?,
    val resolutionVersion: Int?,
    val appliedVersion: Int?,
    val followUpOperationId: String?,
) {
    val isUnresolved: Boolean get() = state == "UNRESOLVED"
}

private fun Map<String, JsonValue>.stringOf(key: String): String =
    (this[key] as? JsonString)?.value ?: ""

private fun Map<String, JsonValue>.intOf(key: String): Int = intOfOrNull(key)
    ?: throw IllegalArgumentException("Missing integer field: $key")

private fun Map<String, JsonValue>.intOfOrNull(key: String): Int? =
    (this[key] as? com.example.EdgeMemo.core.record.JsonNumber)?.value?.toInt()

private fun Map<String, JsonValue>.longOf(key: String): Long = longOfOrNull(key) ?: 0L

private fun Map<String, JsonValue>.longOfOrNull(key: String): Long? =
    (this[key] as? com.example.EdgeMemo.core.record.JsonNumber)?.value?.toLong()

private fun Map<String, JsonValue>.booleanOf(key: String): Boolean =
    (this[key] as? com.example.EdgeMemo.core.record.JsonBoolean)?.value ?: false
