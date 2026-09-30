package com.example.EdgeMemo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.record.JsonString
import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.RecordFilter
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordQuery
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.record.SyncState
import com.example.EdgeMemo.core.sync.CanonicalContentHash
import com.example.EdgeMemo.core.sync.ChangeDetectionOutcome
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.core.sync.SyncOperationId
import com.example.EdgeMemo.core.sync.SyncOperationType
import com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore
import com.example.EdgeMemo.data.local.sync.DefaultQdrantSyncEngine
import com.example.EdgeMemo.data.local.sync.QdrantChangeDetector
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.local.sync.QdrantSyncOperationStore
import com.example.EdgeMemo.data.policy.DefaultPolicyEngine
import com.example.EdgeMemo.data.policy.DefaultRedactionService
import com.example.EdgeMemo.data.sync.UnimplementedQdrantSyncRemote
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 13.2 — the active application data layer is Qdrant Edge.
 *
 * Everything here runs against the REAL JNI shard (production
 * [QdrantEdgeRecordStore] + production detector/engine) and the REAL Room
 * database is kept open alongside the run solely to PROVE it receives no
 * writes. No fake repositories, no mock persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QdrantRecordMemoryRepositoryTest {

    companion object {
        private const val DIMENSION = 512

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    private val appDir = File.createTempFile("p132-", "").apply { delete(); mkdirs() }
    private val store = QdrantEdgeRecordStore(File(appDir, "qdrant_sync_store"))
    private val embeddingService = FeatureHashingEmbeddingService()
    private val operations = QdrantSyncOperationStore(store)
    private val engine = DefaultQdrantSyncEngine(
        recordStore = store,
        operationStore = operations,
        detector = QdrantChangeDetector(store, operations),
        remote = UnimplementedQdrantSyncRemote(),
        cloudKnowledge = object : com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource {
            override suspend fun pullKnowledge(cursor: String?) =
                throw EdgeError.CloudUnavailable("authoring tests do not pull")
        },
    )
    private val repository = QdrantRecordMemoryRepository(
        recordStore = store,
        syncEngine = engine,
        embeddingService = embeddingService,
        policyEngine = DefaultPolicyEngine(DefaultRedactionService()),
    )
    private val room: EdgeMindDatabase = Room.databaseBuilder(
        ApplicationProvider.getApplicationContext(),
        EdgeMindDatabase::class.java,
        "p132-rollback-proof",
    ).build()

    @After
    fun tearDown() {
        runBlocking {
            runCatching { room.close() }
            runCatching { store.close() }
        }
        appDir.deleteRecursively()
    }

    private fun syncNote(title: String, content: String): CreateMemoryInput =
        CreateMemoryInput(
            title = title,
            content = content,
            type = MemoryType.NOTE,
            userSyncChoice = SyncDecision.SYNC,
        )

    private fun createSyncNote(title: String, content: String): Memory = runBlocking {
        repository.create(syncNote(title, content))
    }

    // ------------------------------------------------------------------
    // A/B/C/D/E — CRUD surface
    // ------------------------------------------------------------------

    @Test
    fun createThenRetrieve() = runBlocking {
        val created = repository.create(syncNote("Pump seal spec", "Replace the mechanical seal every 4000 hours"))

        val fetched = repository.get(created.memoryId)
        assertNotNull(fetched)
        assertEquals("Pump seal spec", fetched!!.title)
        assertEquals("Replace the mechanical seal every 4000 hours", fetched.content)
        assertEquals(MemoryType.NOTE, fetched.type)
        assertEquals(1, fetched.version)
        assertEquals(SyncDecision.SYNC, fetched.syncDecision)
        // A sanctioned write went straight to PENDING (change detection ran).
        assertEquals(MemorySyncState.PENDING, fetched.syncState)
    }

    @Test
    fun updateThenRetrieveBumpsVersionAndKeepsIdentity() = runBlocking {
        val created = repository.create(syncNote("Torque table", "M8 bolts take 20 Nm"))
        val updated = repository.update(created.copy(content = "M8 bolts take 24 Nm"))

        assertEquals(2, updated.version)
        val fetched = repository.get(created.memoryId)!!
        assertEquals("M8 bolts take 24 Nm", fetched.content)
        assertEquals(2, fetched.version)
        assertEquals(updated.contentHash, fetched.contentHash)
    }

    @Test
    fun updateMissingMemoryFailsHonestly() {
        assertThrows(EdgeError.MemoryNotFound::class.java) {
            runBlocking {
                repository.update(
                    Memory(
                        memoryId = java.util.UUID.randomUUID().toString(),
                        title = "ghost", content = "nope", chunkId = null, source = "USER_ENTRY",
                        type = MemoryType.NOTE, tags = emptyList(), createdAt = 0, updatedAt = 0,
                        origin = com.example.EdgeMemo.core.model.MemoryOrigin.LOCAL,
                        syncDecision = SyncDecision.LOCAL_ONLY, syncState = MemorySyncState.LOCAL,
                        sensitivity = com.example.EdgeMemo.core.model.MemorySensitivity.STANDARD,
                        importance = 0, version = 1, contentHash = "", subjectKey = null,
                        supersedes = null, tombstone = false, metadata = emptyMap(),
                    ),
                )
            }
        }
    }

    @Test
    fun listReturnsActiveMemoriesMostRecentFirst() = runBlocking {
        createSyncNote("first", "LINE-A bearing change")
        createSyncNote("second", "LINE-B coolant flush")

        val all = repository.list()
        assertEquals(2, all.size)
        // list() preserves the legacy updatedAt-DESC contract.
        assertTrue(all.first().updatedAt >= all.last().updatedAt)
    }

    @Test
    fun filteredTypesAreQueryableOnTheUnifiedShard() = runBlocking {
        repository.create(
            CreateMemoryInput(title = "Startup procedure", content = "Warm the press for 10 minutes",
                type = MemoryType.PROCEDURE),
        )
        repository.create(syncNote("Observation", "Oil sheen near the gearbox"))

        // Envelope record types are indexed and distinct per mapped domain type.
        val procedures = store.count(
            RecordQuery.Count(filter = RecordFilter.activeOnly(), recordTypes = setOf(RecordType.PROCEDURE)),
        )
        val notes = store.count(
            RecordQuery.Count(filter = RecordFilter.activeOnly(), recordTypes = setOf(RecordType.MEMORY)),
        )
        assertEquals(1L, procedures)
        assertEquals(1L, notes)
    }

    @Test
    fun countTracksActiveRecordsOnly() = runBlocking {
        assertEquals(0L, repository.count())
        val a = createSyncNote("a", "Lubrication schedule")
        createSyncNote("b", "Vibration readings")
        assertEquals(2L, repository.count())
        repository.delete(a.memoryId)
        assertEquals(1L, repository.count())
    }

    @Test
    fun searchResolvesSemanticMatchesFromTheSameRecord() = runBlocking {
        createSyncNote("Bearing replacement", "Replace SKF-6205 bearing on the main drive")
        createSyncNote("Coffee recipe", "Grind beans medium for pour over")

        val hits = repository.search("replace the SKF-6205 bearing", limit = 5)
        assertTrue("expected a semantic hit", hits.isNotEmpty())
        assertEquals("Bearing replacement", hits.first().memory.title)
    }

    // ------------------------------------------------------------------
    // F/S — persistence and process restart
    // ------------------------------------------------------------------

    @Test
    fun memoriesAndOperationsSurviveReopen() = runBlocking {
        val created = createSyncNote("Restart probe", "Hydraulic pressure must stay above 90 bar")

        // Simulate process death: drop the handle, rebuild every layer from disk.
        store.close()
        val reopened = QdrantEdgeRecordStore(File(appDir, "qdrant_sync_store"))
        runBlocking { reopened.open() }
        try {
            val stored = reopened.get(RecordId.fromString(created.memoryId))!!
            assertEquals("Restart probe", (stored.payload.getValue("title") as JsonString).value)
            assertEquals(SyncState.PENDING, stored.syncState)
            assertNotNull("vector must round-trip through disk", stored.vector)
            assertEquals(512, stored.vector!!.size)
        } finally {
            reopened.close()
        }
    }

    // ------------------------------------------------------------------
    // G/H — payload-only and vector-bearing points, one shard
    // ------------------------------------------------------------------

    @Test
    fun unifiedShardHoldsVectorRecordsAndPayloadOnlyOperations() = runBlocking {
        val created = createSyncNote("Seal change", "Replace the main shaft seal")
        val op = operations.findByOperationId(
            SyncOperationId.generate(SyncOperationType.UPSERT, RecordId.fromString(created.memoryId), 1),
        )!!

        // Knowledge record: vector-bearing point.
        val record = store.get(RecordId.fromString(created.memoryId))!!
        assertNotNull(record.vector)

        // Operation: genuine payload-only point in the SAME collection.
        val opPoint = store.get(QdrantSyncOperationStore.pointId(op.operationId))!!
        assertNull("payload-only operation point must carry no vector", opPoint.vector)
        assertEquals(RecordType.OUTBOX_OP, opPoint.recordType)
    }

    // ------------------------------------------------------------------
    // I/J — tombstones and version progression
    // ------------------------------------------------------------------

    @Test
    fun deleteTombstonesAndEnqueuesDeterministicTombstoneOperation() = runBlocking {
        val created = createSyncNote("Gasket note", "Swap the manifold gasket at overhaul")

        repository.delete(created.memoryId)

        assertNull(repository.get(created.memoryId))
        assertEquals(emptyList<String>(), repository.list().map { it.memoryId })

        val id = RecordId.fromString(created.memoryId)
        val tombstoneOp = operations.findByOperationId(SyncOperationId.generate(SyncOperationType.TOMBSTONE, id, 2))
        assertNotNull("TOMBSTONE:<uuid>:<version> must be the deterministic identity", tombstoneOp)
        assertEquals(OutboxOperationState.PENDING, tombstoneOp!!.state)

        // The tombstone is durable evidence, never a physical delete.
        val stored = store.get(id)!!
        assertTrue(stored.tombstone)
        assertEquals(2, stored.version)
        assertEquals(SyncState.PENDING, stored.syncState)
    }

    @Test
    fun repeatedDeleteIsIdempotent() = runBlocking {
        val created = createSyncNote("Valve note", "Check the relief valve seating")
        repository.delete(created.memoryId)
        repository.delete(created.memoryId) // second delete must not mint anything

        val id = RecordId.fromString(created.memoryId)
        val stored = store.get(id)!!
        assertEquals(2, stored.version)
        assertNotNull(operations.findByOperationId(SyncOperationId.generate(SyncOperationType.TOMBSTONE, id, 2)))
        assertNull(operations.findByOperationId(SyncOperationId.generate(SyncOperationType.TOMBSTONE, id, 3)))
    }

    @Test
    fun versionProgressesMonotonicallyAcrossUpdates() = runBlocking {
        var memory = createSyncNote("Alignment log", "Initial alignment within 0.2 mm")
        for (expected in 2..4) {
            memory = repository.update(memory.copy(content = "Alignment within 0.$expected mm"))
            assertEquals(expected, memory.version)
            val id = RecordId.fromString(memory.memoryId)
            assertNotNull(operations.findByOperationId(SyncOperationId.generate(SyncOperationType.UPSERT, id, expected)))
        }
    }

    // ------------------------------------------------------------------
    // K — canonical content hash
    // ------------------------------------------------------------------

    @Test
    fun contentHashIsTheFrozenCanonicalHashAndSyncMetadataNeverChangesIt() = runBlocking {
        val created = repository.create(
            CreateMemoryInput(title = "Water sample", content = "pH 7.4 at intake", type = MemoryType.OBSERVATION),
        )
        val stored = store.get(RecordId.fromString(created.memoryId))!!
        val canonical = CanonicalContentHash.hash(MemoryRecordMapper.payloadOf(created))
        assertEquals("the repository must mint canonical 12B hashes", canonical, created.contentHash)
        assertEquals(canonical, stored.contentHash)

        // Stamping sync state (PENDING → SYNCED-style writes) must not change it:
        store.upsert(stored.copy(syncState = SyncState.SYNCED))
        val afterStateChange = store.get(RecordId.fromString(created.memoryId))!!
        assertEquals(canonical, afterStateChange.contentHash)
    }

    // ------------------------------------------------------------------
    // L/M/N — policy
    // ------------------------------------------------------------------

    @Test
    fun localOnlyMemoryIsStoredButProducesNoOperation() = runBlocking {
        val private = repository.create(
            CreateMemoryInput(title = "Note", content = "Garage gate code is 4821", type = MemoryType.NOTE),
        )
        assertEquals(SyncDecision.LOCAL_ONLY, private.syncDecision)
        assertEquals(MemorySyncState.LOCAL, private.syncState)

        assertNotNull(repository.get(private.memoryId))
        assertEquals(0L, operations.countByState(OutboxOperationState.PENDING))
    }

    @Test
    fun syncMemoryProducesExactlyOneSanctionedUpsertOperation() = runBlocking {
        val created = createSyncNote("Shared procedure", "Drain the hydraulic sump quarterly")
        val op = operations.findByOperationId(
            SyncOperationId.generate(SyncOperationType.UPSERT, RecordId.fromString(created.memoryId), 1),
        )!!
        assertEquals(OutboxOperationState.PENDING, op.state)
        assertEquals("SYNC", op.syncDecision.name)
        assertFalse(op.redacted)
        // The sanctioned payload carries the real content.
        assertEquals("Drain the hydraulic sump quarterly",
            (op.payload.getValue("content") as JsonString).value)
    }

    @Test
    fun syncRedactedMemoryOnlyEverSanctionsTheRedactedForm() = runBlocking {
        val repair = repository.create(
            CreateMemoryInput(
                title = "Repair log",
                content = "Replaced pump P-101 for customer Silva, phone 555-0142",
                type = MemoryType.REPAIR,
            ),
        )
        assertEquals(SyncDecision.SYNC_REDACTED, repair.syncDecision)
        assertNotNull(repair.redactedContent)

        val op = operations.findByOperationId(
            SyncOperationId.generate(SyncOperationType.UPSERT, RecordId.fromString(repair.memoryId), 1),
        )!!
        assertTrue("operation must be flagged redacted", op.redacted)
        assertEquals(repair.redactedContent, (op.payload.getValue("content") as JsonString).value)
        val outgoing = op.payload.values.filterIsInstance<JsonString>().map { it.value }
        assertTrue("raw protected identifier must not leave in the op payload",
            outgoing.none { it.contains("P-101") })
    }

    @Test
    fun demotionToLocalOnlyWithdrawsThePendingOperation() = runBlocking {
        val created = createSyncNote("Mixed note", "Routine filter change interval")
        val id = RecordId.fromString(created.memoryId)
        assertNotNull(operations.findByOperationId(SyncOperationId.generate(SyncOperationType.UPSERT, id, 1)))

        // Editing the content into access information trips the HARD local rule.
        repository.update(created.copy(content = "Filter change; also the server admin password is hunter2"))

        assertNull("LOCAL_ONLY demotion must withdraw the still-claimable operation",
            operations.findByOperationId(SyncOperationId.generate(SyncOperationType.UPSERT, id, 2)))
        val stored = store.get(id)!!
        assertEquals(SyncState.LOCAL, stored.syncState)
        assertEquals(com.example.EdgeMemo.core.model.SyncDecision.LOCAL_ONLY, repository.get(created.memoryId)!!.syncDecision)
    }

    // ------------------------------------------------------------------
    // O/P — change detection + deterministic identity
    // ------------------------------------------------------------------

    @Test
    fun repeatedDetectionIsIdempotentSingleOperation() = runBlocking {
        val created = createSyncNote("Idempotency", "Recalibrate the load cell monthly")
        val id = RecordId.fromString(created.memoryId)
        val expectedId = SyncOperationId.generate(SyncOperationType.UPSERT, id, 1)
        assertEquals("UPSERT:" + id.uuid + ":1", expectedId.value)

        // Detecting the same stored version again returns the SAME operation.
        val stored = store.get(id)!!
        val again = engine.enqueueIfChanged(stored)
        assertTrue(
            "repeated detection must be idempotent, got $again",
            again is ChangeDetectionOutcome.AlreadyEnqueued || again is ChangeDetectionOutcome.NoChange,
        )
        assertEquals(1L, operations.countByState(OutboxOperationState.PENDING))
    }

    // ------------------------------------------------------------------
    // Q/R — Room and the legacy outbox receive NOTHING
    // ------------------------------------------------------------------

    @Test
    fun migratedMutationsNeverTouchRoomOrTheLegacyOutbox() = runBlocking {
        val created = repository.create(syncNote("Bypass proof", "Cut the bypass line before servicing"))
        repository.update(created.copy(content = "Cut the bypass line before every service"))
        repository.create(
            CreateMemoryInput(title = "Doc chunk", content = "Chapter three of the press manual",
                type = MemoryType.DOCUMENT),
        )
        repository.delete(created.memoryId)

        // The legacy Room graph is open in this same process — and stayed empty.
        assertEquals(0L, room.memoryDao().count())
        assertEquals(0L, room.syncOutboxDao().countAll())
        assertEquals(0L, room.conflictDao().countUnresolved())
        // All sync identity is Qdrant-native (UPSERT:<uuid>:<version> form).
        val native = operations.listByStates(
            setOf(OutboxOperationState.PENDING, OutboxOperationState.FAILED, OutboxOperationState.IN_FLIGHT),
            limit = 50, offsetId = null,
        ).operations
        assertTrue(native.isNotEmpty())
        assertTrue(native.all { it.operationId.value.startsWith("UPSERT:") || it.operationId.value.startsWith("TOMBSTONE:") })
    }

    // ------------------------------------------------------------------
    // Batch path (ingestion writes through here)
    // ------------------------------------------------------------------

    @Test
    fun createAllPersistsEveryChunkAndFeedsDetectionPerRecord() = runBlocking {
        val inputs = (1..3).map { index ->
            syncNote("Manual section $index", "Maintenance chapter $index describes the $index bearing set")
        }
        val phases = mutableListOf<com.example.EdgeMemo.domain.memory.MemoryWritePhase>()
        val created = repository.createAll(inputs) { phases += it }

        assertEquals(3, created.size)
        assertEquals(
            listOf(com.example.EdgeMemo.domain.memory.MemoryWritePhase.EMBEDDING,
                com.example.EdgeMemo.domain.memory.MemoryWritePhase.STORING),
            phases,
        )
        assertEquals(3L, repository.count())
        assertEquals(3L, operations.countByState(OutboxOperationState.PENDING))
        created.forEach { memory ->
            assertNotNull(operations.findByOperationId(
                SyncOperationId.generate(SyncOperationType.UPSERT, RecordId.fromString(memory.memoryId), 1),
            ))
        }
    }

    @Test
    fun emptyAndBlankWritesAreRejectedWithoutSideEffects() {
        assertThrows(EdgeError.InvalidInput::class.java) {
            runBlocking {
                repository.create(CreateMemoryInput(title = "  ", content = " ", type = MemoryType.NOTE))
            }
        }
        runBlocking {
            assertEquals(0L, repository.count())
            assertEquals(0L, operations.countByState(OutboxOperationState.PENDING))
        }
    }
}
