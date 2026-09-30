package com.example.EdgeMemo.data.local.migration

import android.content.Context
import androidx.room.Room
import com.example.EdgeMemo.ai.embedding.EmbeddingService
import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.LocalRecordStore
import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.record.SyncDecision
import com.example.EdgeMemo.core.record.SyncState
import com.example.EdgeMemo.core.sync.ChangeDetectionOutcome
import com.example.EdgeMemo.core.sync.QdrantSyncEngine
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.local.room.MemoryMappers.toDomain
import com.example.EdgeMemo.data.repository.MemoryRecordMapper
import java.util.UUID

/**
 * Phase 13.4 — deterministic, idempotent, one-time import of pre-cutover
 * Room `memories` rows into the Qdrant-native application shard.
 *
 * Safety properties (all pinned by tests):
 *  - Never opens Room unless BOTH conditions hold: the legacy database file
 *    physically exists AND the completion marker is absent. Fresh installs
 *    never touch Room code at all (the production container has no other
 *    runtime Room dependency).
 *  - Preserves memory ids (UUIDs), content, metadata, tags, policy decision
 *    + explanation, redacted representation, origin, authority, subjectKey,
 *    supersedes, version, tombstones and timestamps.
 *  - Content identity is restamped with the FROZEN canonical hash (12B.2) —
 *    the legacy `SHA256("title\ncontent")` value is deliberately not carried.
 *  - Already-synced rows (legacy `SYNCED`) get the §7.3 watermark set to
 *    their stored version, so they produce NO re-push operations and the
 *    cloud is never asked to accept content under a second identity.
 *  - Never-synced, policy-sanctioned rows produce their deterministic
 *    `UPSERT:<uuid>:<version>` operations through the FROZEN change
 *    detector — never via the legacy outbox, never a second identity.
 *  - LOCAL_ONLY rows stay LOCAL_ONLY (detector refuses + withdraws);
 *    CLOUD-origin rows never echo.
 *  - Idempotent twice over: a per-record existence check skips rows already
 *    imported (crash mid-run resumes without duplication), and the
 *    completion marker short-circuits every later start.
 *  - Nothing is deleted: Room rows, the Room file and the legacy outbox are
 *    left exactly as found (rollback remains possible until a later,
 *    explicit retirement decision).
 *  - Rows whose ids are not valid UUIDs are counted as `skippedInvalid` and
 *    remain in Room — never silently dropped.
 */
class RoomRecordImporter(
    private val appContext: Context,
    private val recordStore: LocalRecordStore,
    private val engine: QdrantSyncEngine,
    private val embeddingService: EmbeddingService,
    private val databaseName: String = LEGACY_DATABASE_NAME,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class Status { NO_LEGACY_DATABASE, ALREADY_COMPLETE, COMPLETED }

    data class Report(
        val status: Status,
        val migrated: Int = 0,
        val skippedExisting: Int = 0,
        val skippedInvalid: Int = 0,
        val operationsEnqueued: Int = 0,
    ) {
        val producedSyncWork: Boolean get() = operationsEnqueued > 0
    }

    suspend fun importIfNeeded(): Report {
        // Cheapest gate FIRST: a fresh install never touches the marker, the
        // shard or Room at all.
        if (!appContext.getDatabasePath(databaseName).exists()) {
            return Report(Status.NO_LEGACY_DATABASE)
        }
        recordStore.ensureReady(embeddingService.dimension)
        recordStore.ensureIndexes()
        if (marker() != null) return Report(Status.ALREADY_COMPLETE)

        val database = Room.databaseBuilder(appContext, EdgeMindDatabase::class.java, databaseName)
            .addMigrations(
                EdgeMindDatabase.MIGRATION_1_2,
                EdgeMindDatabase.MIGRATION_2_3,
                EdgeMindDatabase.MIGRATION_3_4,
            )
            .build()
        try {
            var migrated = 0
            var skippedExisting = 0
            var skippedInvalid = 0
            var operations = 0

            for (row in database.memoryDao().listAll()) {
                val id = runCatching { RecordId.fromString(row.memoryId) }.getOrNull()
                if (id == null) {
                    skippedInvalid++
                    continue
                }
                if (recordStore.get(id) != null) {
                    skippedExisting++
                    continue
                }
                val memory = row.toDomain()
                val canonical = com.example.EdgeMemo.core.sync.CanonicalContentHash.hash(
                    MemoryRecordMapper.payloadOf(memory),
                )
                val synced = memory.syncState == com.example.EdgeMemo.core.model.MemorySyncState.SYNCED
                val vector = runCatching { embed(memory) }.getOrNull()
                val record = MemoryRecordMapper.toRecord(memory, vector).let {
                    it.copy(
                        contentHash = canonical,
                        syncState = when {
                            // Legacy PENDING rows belonged to the frozen Room
                            // outbox; restate them as unsynced LOCAL so the
                            // detector re-mints them under the Qdrant-native
                            // identity exactly once.
                            it.syncState == SyncState.PENDING -> SyncState.LOCAL
                            else -> it.syncState
                        },
                        lastSyncedVersion = if (synced) memory.version else null,
                        lastSyncedOperationId = if (synced) "room-import:$id" else null,
                        lastSyncedContentHash = if (synced) canonical else null,
                    )
                }
                recordStore.upsert(record)
                migrated++

                val outcome = engine.enqueueIfChanged(recordStore.get(id) ?: record)
                if (outcome is ChangeDetectionOutcome.Enqueued ||
                    outcome is ChangeDetectionOutcome.AlreadyEnqueued
                ) {
                    operations++
                }
            }

            writeMarker(migrated, operations)
            return Report(
                status = Status.COMPLETED,
                migrated = migrated,
                skippedExisting = skippedExisting,
                skippedInvalid = skippedInvalid,
                operationsEnqueued = operations,
            )
        } finally {
            runCatching { database.close() }
        }
    }

    // ------------------------------------------------------------------

    private suspend fun marker(): Record? = recordStore.get(MARKER_ID)

    private suspend fun writeMarker(migrated: Int, operations: Int) {
        val existing = marker()
        recordStore.upsert(
            Record(
                id = MARKER_ID,
                recordType = RecordType.SYS_SETTING,
                entityId = null,
                vector = null,
                payload = mapOf(
                    "key" to JsonValue.fromString(MARKER_KEY),
                    "status" to JsonValue.fromString("complete"),
                    "migrated" to JsonValue.fromInt(migrated),
                    "operations" to JsonValue.fromInt(operations),
                    "completed_at" to JsonValue.fromLong(clock()),
                ),
                version = (existing?.version ?: 0) + 1,
                createdAt = existing?.createdAt ?: clock(),
                updatedAt = clock(),
                syncDecision = SyncDecision.LOCAL_ONLY,
            ),
        )
    }

    private suspend fun embed(memory: com.example.EdgeMemo.core.model.Memory): FloatArray {
        val text = buildString {
            append(memory.title)
            if (memory.title.isNotEmpty() && memory.content.isNotEmpty()) append("\n")
            append(memory.content)
        }
        return embeddingService.embed(text)
    }

    companion object {
        /** The legacy production database name (kept for rollback reference). */
        const val LEGACY_DATABASE_NAME = "edge-memory.db"
        const val MARKER_KEY = "room_import_v1"

        /** Deterministic sys-setting point id for the completion marker. */
        val MARKER_ID: RecordId = RecordId.fromString(
            UUID.nameUUIDFromBytes("edgemind:sys:room-import-v1".toByteArray()).toString(),
        )
    }
}
