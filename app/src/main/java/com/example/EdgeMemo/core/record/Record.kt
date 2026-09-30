package com.example.EdgeMemo.core.record

/**
 * Sync state of a record in the local store.
 */
enum class SyncState {
    LOCAL,      // Created locally, not yet in outbox
    PENDING,    // In outbox, awaiting sync
    SYNCED,     // Acknowledged by cloud
    FAILED,     // Sync failed, will retry
}

/**
 * Sync decision for cloud synchronization.
 */
enum class SyncDecision {
    LOCAL_ONLY,
    SYNC,
    SYNC_REDACTED,
}

/**
 * Origin of the record.
 */
enum class RecordOrigin {
    LOCAL,
    CLOUD,
    SYNCED,
}

/**
 * The generic Qdrant-backed record.
 *
 * Envelope fields (system metadata) are distinguished from domain fields by
 * a leading underscore prefix in the stored payload. The envelope is managed
 * by the record layer; domain fields are provided by callers.
 *
 * Envelope fields (all records):
 *   _record_type     - RecordType payload value (keyword)
 *   _schema_version  - Payload schema version (integer, starts at 1)
 *   _entity_id       - Human-readable external ID (keyword, optional)
 *   _version         - Optimistic version counter (integer)
 *   _created_at      - Creation timestamp epoch millis (datetime)
 *   _updated_at      - Last update timestamp epoch millis (datetime)
 *   _source          - Provenance: USER_ENTRY | IMPORT | CLOUD | CURATED (keyword)
 *   _sync_decision   - LOCAL_ONLY | SYNC | SYNC_REDACTED (keyword)
 *   _sync_state      - LOCAL | PENDING | SYNCED | FAILED (keyword)
 *   _subject_key     - Evolving memory subject identity (keyword, optional)
 *   _content_hash    - SHA-256 of title+content for deduplication (keyword, optional)
 *   _supersedes      - Record ID this record supersedes (keyword, optional)
 *   _tombstone       - Soft delete marker (bool)
 *   _deleted_at      - Deletion timestamp (datetime, optional)
 *   _origin          - LOCAL | CLOUD | SYNCED (keyword)
 *   _authority       - Cloud authority provenance (keyword, optional)
 *   _policy_reason   - Explainable policy decision (text, optional)
 *   _redacted_title  - SYNC_REDACTED representation (text, optional)
 *   _redacted_content- SYNC_REDACTED representation (text, optional)
 *   _tags            - Keyword array (optional)
 *   _metadata        - Free-form string map (object, optional)
 *
 * Domain fields are arbitrary key-value pairs stored alongside envelope.
 */
data class Record(
    val id: RecordId,
    val recordType: RecordType,
    val entityId: String?,                    // Human-readable external ID (e.g. "M-042", "P-101")
    val vector: FloatArray?,                  // null = payload-only point
    val payload: Map<String, JsonValue>,      // Domain payload (no envelope fields)
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val source: String = "USER_ENTRY",
    val syncState: SyncState = SyncState.LOCAL,
    val syncDecision: SyncDecision = SyncDecision.LOCAL_ONLY,
    val subjectKey: String? = null,
    val contentHash: String? = null,
    val supersedes: String? = null,
    val tombstone: Boolean = false,
    val deletedAt: Long? = null,
    val origin: RecordOrigin = RecordOrigin.LOCAL,
    val authority: String? = null,
    val policyReason: String? = null,
    val redactedTitle: String? = null,
    val redactedContent: String? = null,
    val tags: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val schemaVersion: Int = 1,
    // ─── Phase 12 §7.3 reconciliation watermark (sync metadata; NEVER part of
    // the canonical content hash and NEVER used for ordering) ───
    /** Highest record version known accepted by the cloud. */
    val lastSyncedVersion: Int? = null,
    /** Last accepted operation identity, for diagnosing uncertain acks. */
    val lastSyncedOperationId: String? = null,
    /** Content hash the cloud holds at [lastSyncedVersion]. */
    val lastSyncedContentHash: String? = null,
) {
    /**
     * Convert to full payload map including envelope fields for storage.
     * Envelope fields use underscore prefix to avoid collision with domain fields.
     */
    fun toFullPayload(): Map<String, JsonValue> {
        val builder = mutableMapOf<String, JsonValue>()
        // Envelope fields
        builder["_record_type"] = JsonValue.fromString(recordType.payloadValue)
        builder["_schema_version"] = JsonValue.fromInt(schemaVersion)
        entityId?.let { builder["_entity_id"] = JsonValue.fromString(it) }
        builder["_version"] = JsonValue.fromInt(version)
        builder["_created_at"] = JsonValue.fromLong(createdAt)
        builder["_updated_at"] = JsonValue.fromLong(updatedAt)
        builder["_source"] = JsonValue.fromString(source)
        builder["_sync_decision"] = JsonValue.fromString(syncDecision.name)
        builder["_sync_state"] = JsonValue.fromString(syncState.name)
        subjectKey?.let { builder["_subject_key"] = JsonValue.fromString(it) }
        contentHash?.let { builder["_content_hash"] = JsonValue.fromString(it) }
        supersedes?.let { builder["_supersedes"] = JsonValue.fromString(it) }
        builder["_tombstone"] = JsonValue.fromBoolean(tombstone)
        deletedAt?.let { builder["_deleted_at"] = JsonValue.fromLong(it) }
        builder["_origin"] = JsonValue.fromString(origin.name)
        authority?.let { builder["_authority"] = JsonValue.fromString(it) }
        policyReason?.let { builder["_policy_reason"] = JsonValue.fromString(it) }
        redactedTitle?.let { builder["_redacted_title"] = JsonValue.fromString(it) }
        redactedContent?.let { builder["_redacted_content"] = JsonValue.fromString(it) }
        if (tags.isNotEmpty()) builder["_tags"] = JsonValue.fromList(tags.map { JsonValue.fromString(it) })
        if (metadata.isNotEmpty()) {
            builder["_metadata"] = JsonValue.fromMap(metadata.mapValues { JsonValue.fromString(it.value) })
        }
        lastSyncedVersion?.let { builder["_last_synced_version"] = JsonValue.fromInt(it) }
        lastSyncedOperationId?.let { builder["_last_synced_operation_id"] = JsonValue.fromString(it) }
        lastSyncedContentHash?.let { builder["_last_synced_content_hash"] = JsonValue.fromString(it) }
        // Domain payload
        builder.putAll(payload)
        return builder
    }

    /**
     * Create a Record from a full payload map (as retrieved from Qdrant).
     * Separates envelope fields from domain fields.
     */
    companion object {
        fun fromFullPayload(
            id: RecordId,
            fullPayload: Map<String, JsonValue>,
        ): Record {
            val recordType = fullPayload.getString("_record_type")
                ?.let { RecordType.fromPayloadValue(it) }
                ?: throw IllegalArgumentException("Missing or invalid _record_type")

            val entityId = fullPayload.getString("_entity_id")
            val version = fullPayload.getInt("_version") ?: 1
            val createdAt = fullPayload.getLong("_created_at") ?: System.currentTimeMillis()
            val updatedAt = fullPayload.getLong("_updated_at") ?: System.currentTimeMillis()
            val source = fullPayload.getString("_source") ?: "USER_ENTRY"
            val syncState = fullPayload.getString("_sync_state")?.let { SyncState.valueOf(it) } ?: SyncState.LOCAL
            val syncDecision = fullPayload.getString("_sync_decision")?.let { SyncDecision.valueOf(it) } ?: SyncDecision.LOCAL_ONLY
            val subjectKey = fullPayload.getString("_subject_key")
            val contentHash = fullPayload.getString("_content_hash")
            val supersedes = fullPayload.getString("_supersedes")
            val tombstone = fullPayload.getBoolean("_tombstone") ?: false
            val deletedAt = fullPayload.getLong("_deleted_at")
            val origin = fullPayload.getString("_origin")?.let { RecordOrigin.valueOf(it) } ?: RecordOrigin.LOCAL
            val authority = fullPayload.getString("_authority")
            val policyReason = fullPayload.getString("_policy_reason")
            val redactedTitle = fullPayload.getString("_redacted_title")
            val redactedContent = fullPayload.getString("_redacted_content")
            val tags = fullPayload.getStringList("_tags") ?: emptyList()
            val metadata = fullPayload.getStringMap("_metadata") ?: emptyMap()
            val schemaVersion = fullPayload.getInt("_schema_version") ?: 1
            val lastSyncedVersion = fullPayload.getInt("_last_synced_version")
            val lastSyncedOperationId = fullPayload.getString("_last_synced_operation_id")
            val lastSyncedContentHash = fullPayload.getString("_last_synced_content_hash")

            // Domain payload = all non-envelope fields
            val envelopeKeys = setOf(
                "_record_type", "_schema_version", "_entity_id", "_version",
                "_created_at", "_updated_at", "_source", "_sync_decision",
                "_sync_state", "_subject_key", "_content_hash", "_supersedes",
                "_tombstone", "_deleted_at", "_origin", "_authority",
                "_policy_reason", "_redacted_title", "_redacted_content",
                "_tags", "_metadata",
                "_last_synced_version", "_last_synced_operation_id",
                "_last_synced_content_hash",
            )
            val domainPayload = fullPayload.filterKeys { it !in envelopeKeys }

            return Record(
                id = id,
                recordType = recordType!!,
                entityId = entityId,
                vector = null, // vector handled separately
                payload = domainPayload,
                version = version,
                createdAt = createdAt,
                updatedAt = updatedAt,
                source = source,
                syncState = syncState,
                syncDecision = syncDecision,
                subjectKey = subjectKey,
                contentHash = contentHash,
                supersedes = supersedes,
                tombstone = tombstone,
                deletedAt = deletedAt,
                origin = origin,
                authority = authority,
                policyReason = policyReason,
                redactedTitle = redactedTitle,
                redactedContent = redactedContent,
                tags = tags,
                metadata = metadata,
                schemaVersion = schemaVersion,
                lastSyncedVersion = lastSyncedVersion,
                lastSyncedOperationId = lastSyncedOperationId,
                lastSyncedContentHash = lastSyncedContentHash,
            )
        }
    }
}

/** Extension functions for safe JsonValue extraction. */
fun Map<String, JsonValue>.getString(key: String): String? = this[key]?.getString()
fun Map<String, JsonValue>.getInt(key: String): Int? = this[key]?.getInt()
fun Map<String, JsonValue>.getLong(key: String): Long? = this[key]?.getLong()
fun Map<String, JsonValue>.getDouble(key: String): Double? = this[key]?.getDouble()
fun Map<String, JsonValue>.getBoolean(key: String): Boolean? = this[key]?.getBoolean()
fun Map<String, JsonValue>.getStringList(key: String): List<String>? = this[key]?.getStringList()
fun Map<String, JsonValue>.getStringMap(key: String): Map<String, String>? = this[key]?.getStringMap()

/** Extension functions for JsonValue concrete types. */
fun JsonValue.getString(): String? = when (this) { is JsonString -> value; else -> null }
fun JsonValue.getInt(): Int? = when (this) { is JsonNumber -> asInt; else -> null }
fun JsonValue.getLong(): Long? = when (this) { is JsonNumber -> asLong; else -> null }
fun JsonValue.getDouble(): Double? = when (this) { is JsonNumber -> asDouble; else -> null }
fun JsonValue.getBoolean(): Boolean? = when (this) { is JsonBoolean -> value; else -> null }
fun JsonValue.getStringList(): List<String>? = when (this) { is JsonArray -> value.mapNotNull { it.getString() }; else -> null }
fun JsonValue.getStringMap(): Map<String, String>? = when (this) { is JsonObject -> value.mapValues { (_, v) -> v.getString() ?: "" }; else -> null }