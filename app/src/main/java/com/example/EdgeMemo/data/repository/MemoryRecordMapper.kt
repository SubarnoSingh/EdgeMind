package com.example.EdgeMemo.data.repository

import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemorySensitivity
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordOrigin
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.record.SyncState
import com.example.EdgeMemo.core.record.getInt
import com.example.EdgeMemo.core.record.getString

/**
 * Phase 13.2 — the canonical, deliberate mapping between the application
 * [Memory] model and the Qdrant-native [Record] model.
 *
 * ## Field placement rules (see docs/PHASE_13_2_QDRANT_APPLICATION_CUTOVER.md)
 *
 * **Record envelope** (`_`-prefixed, EXCLUDED from the canonical content hash
 * by the frozen 12B.2 rule in `CanonicalContentHash`, therefore identity- and
 * sync-metadata — never content):
 *   id, recordType, version, createdAt, updatedAt, source, syncState,
 *   syncDecision, subjectKey, contentHash, supersedes, tombstone, deletedAt,
 *   origin, authority, policyReason, redactedTitle, redactedContent, tags,
 *   metadata, and the §7.3 watermark fields.
 *
 * **Domain payload** (INCLUDED in the canonical content hash — changing any
 * of these is a content change that legitimately re-syncs):
 *   title, content, type, chunkId, sensitivity, importance.
 *
 * `MemoryType` (7 values) and `RecordType` (16 values) are intentionally
 * different axes: `RecordType` scopes store-level filtering/isolation and is
 * chosen deterministically; the authoritative domain type is stored verbatim
 * in the payload (`type` key) so the mapping is fully reversible and NOTHING
 * is silently discarded. `toMemory` reads the payload `type` first and only
 * falls back to the [recordTypeFallback] table for foreign records written
 * without the payload key.
 *
 * Intentionally NOT represented on [Memory] (documented, not discarded):
 *   - `Record.deletedAt` — the app `Memory` model has no deletion timestamp;
 *     it survives in the record envelope for sync/tombstone semantics.
 *   - `Record.lastSyncedVersion/OperationId/ContentHash` — the §7.3 sync
 *     watermark lives only at the record layer; the app model never consumed
 *     it and it must not leak into domain logic.
 *   - `Record.entityId` — reserved for future domain entities (M-042 style);
 *     application memories have no external entity id.
 */
internal object MemoryRecordMapper {

    const val FIELD_TITLE = "title"
    const val FIELD_CONTENT = "content"
    const val FIELD_TYPE = "type"
    const val FIELD_CHUNK_ID = "chunkId"
    const val FIELD_SENSITIVITY = "sensitivity"
    const val FIELD_IMPORTANCE = "importance"

    /**
     * The envelope `RecordType` axis of every application memory. Shared by
     * the repository writes and the Phase 13.3 retrieval scope so authoring
     * and retrieval agree on exactly one set — sync/system points
     * (`outbox_op`, `conflict`, `sys_cursor`, …) are structurally outside it.
     */
    val APP_MEMORY_RECORD_TYPES: Set<RecordType> = setOf(
        RecordType.MEMORY,
        RecordType.DOCUMENT,
        RecordType.PROCEDURE,
        RecordType.INSPECTION,
        RecordType.MAINTENANCE_RECORD,
        RecordType.FAILURE,
    )

    /** Memory domain fields that participate in content identity. */
    fun payloadOf(memory: Memory): Map<String, JsonValue> = buildMap {
        put(FIELD_TITLE, JsonValue.fromString(memory.title))
        put(FIELD_CONTENT, JsonValue.fromString(memory.content))
        put(FIELD_TYPE, JsonValue.fromString(memory.type.name))
        memory.chunkId?.let { put(FIELD_CHUNK_ID, JsonValue.fromString(it)) }
        put(FIELD_SENSITIVITY, JsonValue.fromString(memory.sensitivity.name))
        put(FIELD_IMPORTANCE, JsonValue.fromInt(memory.importance))
    }

    fun recordTypeFor(type: MemoryType, origin: MemoryOrigin): RecordType = when {
        origin == MemoryOrigin.CLOUD && type == MemoryType.CLOUD_KNOWLEDGE -> RecordType.MEMORY
        else -> when (type) {
            MemoryType.DOCUMENT -> RecordType.DOCUMENT
            MemoryType.NOTE -> RecordType.MEMORY
            MemoryType.OBSERVATION -> RecordType.INSPECTION
            MemoryType.PROCEDURE -> RecordType.PROCEDURE
            MemoryType.REPAIR -> RecordType.MAINTENANCE_RECORD
            MemoryType.EVENT -> RecordType.FAILURE
            MemoryType.CLOUD_KNOWLEDGE -> RecordType.MEMORY
        }
    }

    /** Only for records written by other producers (no payload `type`). */
    private fun recordTypeFallback(type: RecordType, origin: RecordOrigin): MemoryType =
        if (origin == RecordOrigin.CLOUD) {
            MemoryType.CLOUD_KNOWLEDGE
        } else {
            when (type) {
                RecordType.DOCUMENT, RecordType.DOCUMENT_CHUNK -> MemoryType.DOCUMENT
                RecordType.PROCEDURE -> MemoryType.PROCEDURE
                RecordType.MAINTENANCE_RECORD -> MemoryType.REPAIR
                RecordType.FAILURE -> MemoryType.EVENT
                RecordType.INSPECTION -> MemoryType.OBSERVATION
                else -> MemoryType.NOTE // MEMORY, MACHINE, PART, TECHNICIAN, LOCATION …
            }
        }

    fun toRecord(memory: Memory, vector: FloatArray?): Record = Record(
        id = RecordId.fromString(memory.memoryId),
        recordType = recordTypeFor(memory.type, memory.origin),
        entityId = null,
        vector = vector,
        payload = payloadOf(memory),
        version = memory.version,
        createdAt = memory.createdAt,
        updatedAt = memory.updatedAt,
        source = memory.source,
        syncState = SyncState.valueOf(memory.syncState.name),
        // core.record.SyncDecision is a DISTINCT enum from the imported
        // core.model.SyncDecision — fully qualified to avoid a silent mixup.
        syncDecision = com.example.EdgeMemo.core.record.SyncDecision.valueOf(
            memory.syncDecision.name,
        ),
        subjectKey = memory.subjectKey,
        contentHash = memory.contentHash,
        supersedes = memory.supersedes,
        tombstone = memory.tombstone,
        deletedAt = null,
        origin = RecordOrigin.valueOf(memory.origin.name),
        authority = memory.authority,
        policyReason = memory.policyReason,
        redactedTitle = memory.redactedTitle,
        redactedContent = memory.redactedContent,
        tags = memory.tags,
        metadata = memory.metadata,
    )

    fun toMemory(record: Record): Memory {
        val payload = record.payload
        val type = payload.getString(FIELD_TYPE)
            ?.let { name -> MemoryType.entries.firstOrNull { it.name == name } }
            ?: recordTypeFallback(record.recordType, record.origin)
        return Memory(
            memoryId = record.id.uuid,
            title = payload.getString(FIELD_TITLE).orEmpty(),
            content = payload.getString(FIELD_CONTENT).orEmpty(),
            chunkId = payload.getString(FIELD_CHUNK_ID),
            source = record.source,
            type = type,
            tags = record.tags,
            createdAt = record.createdAt,
            updatedAt = record.updatedAt,
            origin = MemoryOrigin.valueOf(record.origin.name),
            syncDecision = SyncDecision.valueOf(record.syncDecision.name),
            syncState = MemorySyncState.valueOf(record.syncState.name),
            sensitivity = payload.getString(FIELD_SENSITIVITY)
                ?.let { name -> MemorySensitivity.entries.firstOrNull { it.name == name } }
                ?: MemorySensitivity.STANDARD,
            importance = payload.getInt(FIELD_IMPORTANCE) ?: 0,
            version = record.version,
            contentHash = record.contentHash ?: "",
            subjectKey = record.subjectKey,
            supersedes = record.supersedes,
            tombstone = record.tombstone,
            metadata = record.metadata,
            policyReason = record.policyReason,
            redactedTitle = record.redactedTitle,
            redactedContent = record.redactedContent,
            authority = record.authority,
        )
    }
}
