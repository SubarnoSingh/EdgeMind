package com.example.EdgeMemo.data.local.migration

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemorySensitivity
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.record.JsonString
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordQuery
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.record.SyncState
import com.example.EdgeMemo.core.sync.CanonicalContentHash
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.local.room.MemoryEntity
import com.example.EdgeMemo.data.local.sync.DefaultQdrantSyncEngine
import com.example.EdgeMemo.data.local.sync.QdrantChangeDetector
import com.example.EdgeMemo.data.local.sync.QdrantSyncOperationStore
import com.example.EdgeMemo.data.repository.MemoryRecordMapper
import com.example.EdgeMemo.data.sync.QdrantNativeCloudKnowledgeIngestor
import com.example.EdgeMemo.data.sync.UnimplementedQdrantSyncRemote
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeBatch
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 13.4 — the one-time Room→Record importer. Seeded against a REAL
 * Room database (production schema + mappers) and a REAL Qdrant shard;
 * verifies preservation, watermark semantics, operation re-minting, idempotency,
 * invalid-id safety and "nothing is deleted".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomRecordImporterTest {

    companion object {
        private const val DIMENSION = 512

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val shardDir = File.createTempFile("p134-import-", "").apply { delete(); mkdirs() }
    private val store = QdrantEdgeRecordStore(File(shardDir, "qdrant_sync_store"))
    private val embeddings = FeatureHashingEmbeddingService()
    private val operations = QdrantSyncOperationStore(store)
    private val engine = DefaultQdrantSyncEngine(
        recordStore = store,
        operationStore = operations,
        detector = QdrantChangeDetector(store, operations),
        remote = UnimplementedQdrantSyncRemote(),
        cloudKnowledge = object : CloudKnowledgeRemoteDataSource {
            override suspend fun pullKnowledge(cursor: String?) = CloudKnowledgeBatch(emptyList())
        },
    )

    // The importer opens Room through ApplicationProvider's databases dir.
    private fun legacyDatabase(): EdgeMindDatabase = Room.databaseBuilder(
        app, EdgeMindDatabase::class.java, RoomRecordImporter.LEGACY_DATABASE_NAME,
    ).build()

    private fun entity(
        id: String = UUID.randomUUID().toString(),
        title: String = "Note",
        content: String = "Content",
        type: MemoryType = MemoryType.NOTE,
        origin: MemoryOrigin = MemoryOrigin.LOCAL,
        decision: SyncDecision = SyncDecision.LOCAL_ONLY,
        state: MemorySyncState = MemorySyncState.LOCAL,
        version: Int = 1,
        tombstone: Boolean = false,
        tags: List<String> = emptyList(),
        metadata: Map<String, String> = emptyMap(),
        subjectKey: String? = null,
    ): MemoryEntity = MemoryEntity(
        memoryId = id, title = title, content = content, chunkId = null,
        source = "IMPORT_TEST", type = type.name, tags = tags,
        createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_500L,
        origin = origin.name, syncDecision = decision.name, syncState = state.name,
        sensitivity = MemorySensitivity.STANDARD.name, importance = 3, version = version,
        contentHash = "legacy-hash-$version", subjectKey = subjectKey, supersedes = null,
        tombstone = tombstone, metadata = metadata, policyReason = "test",
        redactedTitle = null, redactedContent = null, authority = null,
    )

    @After
    fun tearDown() {
        runBlocking { runCatching { store.close() } }
        shardDir.deleteRecursively()
    }

    private fun importer() = RoomRecordImporter(
        appContext = app,
        recordStore = store,
        engine = engine,
        embeddingService = embeddings,
    )

    @Test
    fun freshInstallWithoutLegacyDatabaseNeverTouchesRoom() {
        // Robolectric starts with no edge-memory.db file on disk.
        val report = runBlocking { importer().importIfNeeded() }
        assertEquals(RoomRecordImporter.Status.NO_LEGACY_DATABASE, report.status)
        assertTrue(!app.getDatabasePath(RoomRecordImporter.LEGACY_DATABASE_NAME).exists())
    }

    @Test
    fun importPreservesRecordsWatermarksPolicyAndMintsOpsOnlyWhereNeeded() = runBlocking {
        val db = legacyDatabase()
        val syncedId = UUID.randomUUID().toString()
        val pendingSyncId = UUID.randomUUID().toString()
        val localOnlyId = UUID.randomUUID().toString()
        val cloudId = UUID.randomUUID().toString()
        val tombstonedId = UUID.randomUUID().toString()
        db.memoryDao().insertAll(
            listOf(
                entity(id = syncedId, title = "Synced note", content = "Already in cloud",
                    decision = SyncDecision.SYNC, state = MemorySyncState.SYNCED, version = 4),
                entity(id = pendingSyncId, title = "Queued note", content = "Never delivered",
                    decision = SyncDecision.SYNC, state = MemorySyncState.PENDING, version = 1),
                entity(id = localOnlyId, title = "Gate code", content = "Door code 4821",
                    decision = SyncDecision.LOCAL_ONLY, state = MemorySyncState.LOCAL),
                entity(id = cloudId, title = "Cloud knowledge", content = "From cloud pull",
                    origin = MemoryOrigin.CLOUD, decision = SyncDecision.SYNC,
                    state = MemorySyncState.SYNCED, version = 2),
                entity(id = tombstonedId, title = "Removed", content = "was deleted pre-cutover",
                    decision = SyncDecision.SYNC, state = MemorySyncState.SYNCED,
                    version = 2, tombstone = true),
                entity(id = "legacy-non-uuid-id", title = "Weird", content = "bad id"),
            ),
        )
        db.close()

        val report = importer().importIfNeeded()
        assertEquals(RoomRecordImporter.Status.COMPLETED, report.status)
        assertEquals(5, report.migrated)
        assertEquals(1, report.skippedInvalid)

        store.ensureReady(DIMENSION); store.ensureIndexes()

        // Field preservation through the shared mapper.
        val synced = store.get(RecordId.fromString(syncedId))!!
        assertEquals("Synced note", (synced.payload.getValue("title") as JsonString).value)
        assertEquals(4, synced.version)
        assertEquals(com.example.EdgeMemo.core.record.SyncDecision.SYNC, synced.syncDecision)
        assertEquals("IMPORT_TEST", synced.source)

        // Already-synced rows carry the §7.3 watermark → they re-push nothing.
        assertEquals(SyncState.SYNCED, synced.syncState)
        assertEquals(4, synced.lastSyncedVersion)
        assertNull(store.get(RecordId.fromString(syncedId))?.let {
            operations.findByOperationId(
                com.example.EdgeMemo.core.sync.SyncOperationId.generate(
                    com.example.EdgeMemo.core.sync.SyncOperationType.UPSERT, it.id, it.version,
                ),
            )
        })

        // Never-synced SYNC rows mint their Qdrant-native identity exactly once.
        val pendingOp = operations.findByOperationId(
            com.example.EdgeMemo.core.sync.SyncOperationId.generate(
                com.example.EdgeMemo.core.sync.SyncOperationType.UPSERT,
                RecordId.fromString(pendingSyncId), 1,
            ),
        )
        assertNotNull("re-minted operation must exist for the undelivered row", pendingOp)
        assertEquals(OutboxOperationState.PENDING, pendingOp!!.state)

        // LOCAL_ONLY preserved; it produced no operation.
        val local = store.get(RecordId.fromString(localOnlyId))!!
        assertEquals(com.example.EdgeMemo.core.record.SyncDecision.LOCAL_ONLY, local.syncDecision)
        // LOCAL_ONLY + CLOUD-origin + already-synced + tombstone rows = 1 op total.
        assertEquals(1L, operations.countByState(OutboxOperationState.PENDING))

        // Cloud-origin rows never echo.
        val cloudRecord = store.get(RecordId.fromString(cloudId))!!
        assertEquals(com.example.EdgeMemo.core.record.RecordOrigin.CLOUD, cloudRecord.origin)
        assertEquals(2, cloudRecord.lastSyncedVersion)

        // Tombstones stay tombstones (never resurrected, never re-deleted).
        val dead = store.get(RecordId.fromString(tombstonedId))!!
        assertTrue(dead.tombstone)
        assertEquals(SyncState.SYNCED, dead.syncState)

        // Canonical hash restamping: record identity is the frozen 12B value.
        val canonical = CanonicalContentHash.hash(MemoryRecordMapper.payloadOf(synced.toDomainMemory()))
        assertEquals(canonical, synced.contentHash)
    }

    @Test
    fun importerIsIdempotentAcrossRerunsAndCrashes() = runBlocking {
        val db = legacyDatabase()
        val id = UUID.randomUUID().toString()
        db.memoryDao().insert(entity(id = id, title = "Once", content = "Only once",
            decision = SyncDecision.SYNC, state = MemorySyncState.LOCAL))
        db.close()

        val first = importer().importIfNeeded()
        assertEquals(1, first.migrated)
        assertEquals(1, first.operationsEnqueued)

        // Marker present → complete no-op.
        val second = importer().importIfNeeded()
        assertEquals(RoomRecordImporter.Status.ALREADY_COMPLETE, second.status)

        // Simulated crash before the marker: rerun must not duplicate the
        // record or mint a second operation.
        runBlocking { store.delete(RoomRecordImporter.MARKER_ID) }
        val third = importer().importIfNeeded()
        assertEquals(0, third.migrated) // existence skip per record
        assertEquals(1, third.skippedExisting)
        assertEquals(1L, operations.countByState(OutboxOperationState.PENDING))

        // Room rows are never deleted by the import (rollback remains).
        val reopened = legacyDatabase()
        assertEquals(1L, reopened.memoryDao().count())
        reopened.close()
    }

    @Test
    fun importedRecordsAreImmediatelyLiveInRetrievalAndList() = runBlocking {
        val db = legacyDatabase()
        val id = UUID.randomUUID().toString()
        db.memoryDao().insert(entity(id = id, title = "P-101 seal inventory",
            content = "Spare mechanical seals for P-101 are stored in cage B4.",
            decision = SyncDecision.SYNC, state = MemorySyncState.LOCAL))
        db.close()
        importer().importIfNeeded()

        store.ensureReady(DIMENSION); store.ensureIndexes()
        val list = com.example.EdgeMemo.data.repository.QdrantRecordMemoryRepository(
            recordStore = store,
            syncEngine = engine,
            embeddingService = embeddings,
        )
        assertTrue(list.list().any { it.memoryId == id })
        val evidence = com.example.EdgeMemo.data.retrieval.QdrantRecordRetrievalService(embeddings, store)
            .retrieve(
                com.example.EdgeMemo.core.retrieval.RetrievalQuery(
                    text = "P-101 spare mechanical seals cage", limit = 5,
                ),
            )
        assertNotNull(evidence.evidence.firstOrNull { it.memory.memoryId == id })
    }

    private fun com.example.EdgeMemo.core.record.Record.toDomainMemory() = MemoryRecordMapper.toMemory(this)
}
