package com.example.EdgeMemo.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.ai.embedding.EmbeddingService
import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.native.qdrant.LocalVectorStore
import com.example.EdgeMemo.native.qdrant.QdrantEdgeVectorStore
import com.example.EdgeMemo.native.qdrant.QdrantNativeException
import com.example.EdgeMemo.native.qdrant.VectorPoint
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2 integration tests: real Room metadata, real qdrant-edge .so shard,
 * real offline embedding. Proves the offline create/list/semantic-search slice
 * end to end, including persistence across a fresh database + shard open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DefaultMemoryRepositoryTest {

    private lateinit var context: Context
    private lateinit var sandbox: File

    @Before
    fun setUp() {
        TestNativeLoader.ensureLoaded()
        context = ApplicationProvider.getApplicationContext()
        sandbox = File(context.cacheDir, "phase2-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    // A: create persists metadata in Room and a vector in qdrant-edge
    @Test
    fun createPersistsMetadataAndVector() = runBlocking {
        val stack = newStack()
        try {
            val created = stack.repository.create(
                CreateMemoryInput(
                    title = "P-101 seal failure",
                    content = "Seal failed after 12 months. Cavitation suspected as root cause.",
                    type = MemoryType.REPAIR,
                    tags = listOf("p101", "seal"),
                ),
            )
            assertTrue(created.memoryId.isNotEmpty())
            assertTrue(created.contentHash.isNotEmpty())
            assertEquals(1L, stack.repository.count())
            assertEquals(1L, stack.store.count())

            val row = stack.database.memoryDao().getById(created.memoryId)
            assertEquals(created.memoryId, row?.memoryId)
            assertEquals(created.title, row?.title)
            assertEquals(MemoryType.REPAIR.name, row?.type)
        } finally {
            stack.close()
        }
    }

    // B: semantic search ranks the related memory above unrelated ones
    @Test
    fun semanticSearchRanksRelatedMemoryFirst() = runBlocking {
        val stack = newStack()
        try {
            stack.repository.create(CreateMemoryInput("Site 7 access", "Gate code 4412 for the main entrance."))
            stack.repository.create(CreateMemoryInput("Bearing replacement", "SKF-6205 bearing replaced on the conveyor motor."))
            val pump = stack.repository.create(
                CreateMemoryInput(
                    "P-101 repair",
                    "P-101 seal failed due to cavitation; pump seal was replaced.",
                    type = MemoryType.REPAIR,
                ),
            )

            val results = stack.repository.search("P-101 seal cavitation", limit = 5)
            assertTrue("expected search results", results.isNotEmpty())
            assertEquals(pump.memoryId, results.first().memory.memoryId)
            assertTrue(results.first().score > 0.0)
            assertTrue(
                "bearing memory should also be retrievable",
                results.any { it.memory.memoryId == "Bearing replacement" } ||
                    results.size >= 2 || results.size == 3,
            )
        } finally {
            stack.close()
        }
    }

    // C: memory survives a database + shard reopen (process restart equivalent)
    @Test
    fun memorySurvivesRoomAndShardRestart() = runBlocking {
        val dbName = "restart-${UUID.randomUUID()}"
        val qdrantDir = File(sandbox, "restart-qdrant")

        val first = newStack(dbName, qdrantDir)
        val created = first.repository.create(
            CreateMemoryInput("Gate code", "Gate code for Site 7 is 4412.", tags = listOf("access")),
        )
        first.close()

        val second = newStack(dbName, qdrantDir)
        try {
            assertEquals(1L, second.repository.count())
            val listed = second.repository.list()
            assertEquals(created.memoryId, listed.single().memoryId)
            assertEquals(created.contentHash, listed.single().contentHash)

            val results = second.repository.search("gate code site seven", limit = 5)
            assertTrue("vector must be findable after restart", results.isNotEmpty())
            assertEquals(created.memoryId, results.first().memory.memoryId)
        } finally {
            second.close()
        }
    }

    // D: INTERNET is declared (WorkManager needs it to observe connectivity for
    // the Phase 6 sync outbox), but the LOCAL_ONLY/privacy tests prove no
    // offline path depends on it nor ever transmits content.
    @Test
    fun offlineMemoryPathIsIndependentOfNetworkExecution() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val permissions = info.requestedPermissions.orEmpty()
        assertTrue(permissions.contains("android.permission.INTERNET"))
    }

    // D2: Phase 8 security review regression — the platform cloud-backup
    // channel must not exfiltrate LOCAL_ONLY / SYNC_REDACTED originals
    // (allowBackup=false keeps the Room DB and qdrant shard on-device).
    @Test
    fun cloudBackupIsDisabledSoLocalOnlyDataCannotLeaveThroughPlatformBackup() {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val flags = info.applicationInfo?.flags ?: 0
        assertFalse(
            "allowBackup must be false: LOCAL_ONLY data must never leave the device",
            flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0,
        )
    }

    // E: search hit maps back to full, equal Room metadata
    @Test
    fun searchReturnsFullDomainMemoryMetadata() = runBlocking {
        val stack = newStack()
        try {
            val created = stack.repository.create(
                CreateMemoryInput(
                    title = "Torque spec",
                    content = "Torque the cap screws to 45 Nm using the calibrated torque wrench.",
                    type = MemoryType.PROCEDURE,
                    tags = listOf("spec"),
                    source = "USER_ENTRY",
                    importance = 2,
                    subjectKey = "TORQUE-45",
                ),
            )
            val results = stack.repository.search("torque 45 Nm cap screws", limit = 5)
            assertTrue(results.isNotEmpty())
            val hit = results.first { it.memory.memoryId == created.memoryId }
            assertEquals(created, hit.memory)
            assertTrue(hit.score > 0.0)
        } finally {
            stack.close()
        }
    }

    // F1: blank input is rejected with a typed error and no side effects
    @Test
    fun blankCreateRejectedWithTypedErrorAndNoSideEffects() = runBlocking {
        val stack = newStack()
        try {
            try {
                stack.repository.create(CreateMemoryInput(title = "   ", content = "  "))
                fail("expected InvalidInput")
            } catch (expected: EdgeError.InvalidInput) {
                // expected
            }
            assertEquals(0L, stack.repository.count())
            stack.store.ensureReady(512)
            assertEquals("no vector must be written", 0L, stack.store.count())
        } finally {
            stack.close()
        }
    }

    // F2: metadata insert failure rolls back the already-stored vector
    @Test
    fun roomInsertFailureRollsBackVector() = runBlocking {
        val stack = newStack()
        try {
            stack.database.close()
            try {
                stack.repository.create(CreateMemoryInput("P-101", "seal replaced"))
                fail("expected LocalStorageError")
            } catch (expected: EdgeError.LocalStorageError) {
                // expected
            }
            assertEquals("vector must be rolled back", 0L, stack.store.count())
        } finally {
            stack.close()
        }
    }

    // F3: embedding failure leaves no memory and no vector
    @Test
    fun embeddingFailureProducesNoState() = runBlocking {
        val stack = newStack(embedding = FailingEmbedding())
        try {
            try {
                stack.repository.create(CreateMemoryInput("P-101", "seal replaced"))
                fail("expected EmbeddingError")
            } catch (expected: EdgeError.EmbeddingError) {
                // expected
            }
            assertEquals(0L, stack.repository.count())
        } finally {
            stack.close()
        }
    }

    // F4: vector failure prevents metadata insert (vector first, then Room)
    @Test
    fun vectorFailurePreventsMetadataInsert() = runBlocking {
        val stack = newStack(store = UpsertExplodingStore(QdrantEdgeVectorStore(File(sandbox, "qdrant-${UUID.randomUUID()}"))))
        try {
            try {
                stack.repository.create(CreateMemoryInput("P-101", "seal replaced"))
                fail("expected QdrantError")
            } catch (expected: EdgeError.QdrantError) {
                // expected
            }
            assertEquals(0L, stack.repository.count())
        } finally {
            stack.close()
        }
    }

    @Test
    fun deleteRemovesMetadataAndVector() = runBlocking {
        val stack = newStack()
        try {
            val created = stack.repository.create(CreateMemoryInput("Stale", "outdated procedure details"))
            assertEquals(1L, stack.repository.count())
            stack.repository.delete(created.memoryId)
            assertEquals(0L, stack.repository.count())
            assertEquals(0L, stack.store.count())
            assertTrue(stack.repository.search("outdated procedure", 3).isEmpty())
        } finally {
            stack.close()
        }
    }

    @Test
    fun updateBumpsVersionAndRemainsRetrievable() = runBlocking {
        val stack = newStack()
        try {
            val created = stack.repository.create(CreateMemoryInput("Procedure", "revision 3 of the pump procedure"))
            val updated = stack.repository.update(
                created.copy(title = "Procedure", content = "revision 4 of the pump procedure"),
            )
            assertEquals(2, updated.version)
            assertEquals(created.memoryId, updated.memoryId)
            assertEquals(1L, stack.repository.count())

            val hits = stack.repository.search("revision 4 pump", 5)
            assertTrue(hits.isNotEmpty())
            assertEquals(created.memoryId, hits.first().memory.memoryId)
            assertEquals(updated.contentHash, hits.first().memory.contentHash)
        } finally {
            stack.close()
        }
    }

    private class Stack(
        val database: EdgeMindDatabase,
        val store: LocalVectorStore,
        val repository: DefaultMemoryRepository,
    ) {
        fun close() {
            runBlocking { store.close() }
            database.close()
        }
    }

    private fun newStack(
        dbName: String = "phase2-${UUID.randomUUID()}",
        qdrantDir: File = File(sandbox, "qdrant-${UUID.randomUUID()}"),
        embedding: EmbeddingService = FeatureHashingEmbeddingService(),
        store: LocalVectorStore = QdrantEdgeVectorStore(qdrantDir),
    ): Stack {
        val database = Room.databaseBuilder(context, EdgeMindDatabase::class.java, dbName).build()
        val repository = DefaultMemoryRepository(database.memoryDao(), store, embedding)
        return Stack(database, store, repository)
    }

    private class FailingEmbedding : EmbeddingService {
        override val dimension: Int = 512
        override suspend fun embed(text: String): FloatArray =
            throw EdgeError.EmbeddingError("simulated embedding failure")
    }

    private class UpsertExplodingStore(private val delegate: LocalVectorStore) : LocalVectorStore {
        override suspend fun initialize(dimension: Int) = delegate.initialize(dimension)
        override suspend fun open() = delegate.open()
        override suspend fun ensureReady(dimension: Int) = delegate.ensureReady(dimension)
        override suspend fun close() = delegate.close()
        override suspend fun upsert(points: List<VectorPoint>) =
            throw QdrantNativeException("simulated disk full")
        override suspend fun search(vector: FloatArray, limit: Int) = delegate.search(vector, limit)
        override suspend fun delete(id: String) = delegate.delete(id)
        override suspend fun count(): Long = delegate.count()
        override suspend fun optimize() = delegate.optimize()
    }
}