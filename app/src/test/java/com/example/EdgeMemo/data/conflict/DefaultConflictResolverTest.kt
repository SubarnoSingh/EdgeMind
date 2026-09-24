package com.example.EdgeMemo.data.conflict

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.ai.embedding.EmbeddingService
import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.data.cloud.DefaultCloudKnowledgeIngestor
import com.example.EdgeMemo.data.cloud.DefaultCloudKnowledgeWriter
import com.example.EdgeMemo.data.cloud.DefaultKnowledgeClassifier
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.repository.DefaultMemoryRepository
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeBatch
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem
import com.example.EdgeMemo.domain.conflict.ConflictResolutionAction
import com.example.EdgeMemo.domain.conflict.ConflictResolutionState
import com.example.EdgeMemo.native.qdrant.LocalVectorStore
import com.example.EdgeMemo.native.qdrant.QdrantEdgeVectorStore
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Conflict resolution integration: real Room, real qdrant-edge .so, real
 * deterministic embedding. Evidence survives: the conflict row keeps both
 * sides after every resolution; keep-cloud materializes the accepted record
 * and preserves the old local record as history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DefaultConflictResolverTest {

    private lateinit var context: Context
    private lateinit var sandbox: File

    @Before
    fun setUp() {
        TestNativeLoader.ensureLoaded()
        context = ApplicationProvider.getApplicationContext()
        sandbox = File(context.cacheDir, "resolver-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    @Test
    fun keepCloudMakesIncomingActiveAndTombstonesLocalAsHistory() = runBlocking {
        val stack = newStack()
        try {
            val local = stack.repository.create(
                CreateMemoryInput("Torque spec", "Torque the cap screws to 45 Nm.", type = MemoryType.PROCEDURE, subjectKey = "T-1"),
            )
            val conflictId = pullConflict(stack, "T-1", "Torque the cap screws to 52 Nm.")
            assertEquals(local.memoryId, stack.database.conflictDao().listAll().single().localMemoryId)

            val resolved = stack.resolver.keepCloud(conflictId, "central procedure wins")
            assertEquals(ConflictResolutionState.RESOLVED_CLOUD, resolved.state)
            assertEquals("central procedure wins", resolved.resolution)

            // accepted record is active and searchable
            val incoming = stack.database.memoryDao().getById(pointId("cloud-t-1"))
            assertEquals("Torque the cap screws to 52 Nm.", incoming?.content)
            assertEquals("CLOUD", incoming?.origin)
            assertEquals(MemoryType.CLOUD_KNOWLEDGE.name, incoming?.type)
            assertEquals("central-engineering", incoming?.authority)

            // old divergent local record preserved as history, not destroyed
            val old = stack.database.memoryDao().getById(local.memoryId)
            assertEquals(true, old?.tombstone)
            assertEquals("Torque the cap screws to 45 Nm.", old?.content)

            // only the accepted record serves offline retrieval
            val hits = stack.repository.search("torque 52 Nm", 5)
            assertTrue(hits.isNotEmpty())
            assertTrue(hits.all { it.memory.memoryId == pointId("cloud-t-1") })
            stack.store.ensureReady(512)
            assertEquals(1L, stack.store.count())
        } finally {
            stack.close()
        }
    }

    @Test
    fun keepCloudSameIdentityOverwritesInPlace() = runBlocking {
        val stack = newStack()
        try {
            val local = stack.repository.create(
                CreateMemoryInput("Torque", "Torque to 45 Nm.", type = MemoryType.PROCEDURE, subjectKey = "T-2"),
            )
            val batch = CloudKnowledgeBatch(
                listOf(cloudItem(local.memoryId, "T-2", "Torque to 52 Nm.", "h52", version = 2)),
            )
            stack.remote.add(batch)
            stack.ingestor.pullAndApply()
            val conflictId = stack.database.conflictDao().listAll().single().conflictId

            stack.resolver.keepCloud(conflictId)

            val row = stack.database.memoryDao().getById(local.memoryId)
            assertEquals("Torque to 52 Nm.", row?.content)
            assertEquals(2, row?.version)
            assertEquals(false, row?.tombstone)
            stack.store.ensureReady(512)
            assertEquals(1L, stack.store.count())
        } finally {
            stack.close()
        }
    }

    @Test
    fun keepLocalLeavesDeviceKnowledgeUntouched() = runBlocking {
        val stack = newStack()
        try {
            val local = stack.repository.create(
                CreateMemoryInput("Torque spec", "Torque the cap screws to 45 Nm.", type = MemoryType.PROCEDURE, subjectKey = "T-3"),
            )
            val conflictId = pullConflict(stack, "T-3", "Torque the cap screws to 52 Nm.")

            stack.resolver.keepLocal(conflictId, "device measured values")

            val conflict = stack.database.conflictDao().getById(conflictId)
            assertEquals(ConflictResolutionState.RESOLVED_LOCAL.name, conflict?.state)
            assertEquals("device measured values", conflict?.resolution)

            val row = stack.database.memoryDao().getById(local.memoryId)
            assertEquals("Torque the cap screws to 45 Nm.", row?.content)
            assertEquals(false, row?.tombstone)
            assertTrue(stack.repository.search("torque 45 Nm", 5).isNotEmpty())
        } finally {
            stack.close()
        }
    }

    @Test
    fun dismissKeepsLocalActiveAndRetainsIncomingEvidence() = runBlocking {
        val stack = newStack()
        try {
            val local = stack.repository.create(
                CreateMemoryInput("Torque", "Torque to 45 Nm.", type = MemoryType.PROCEDURE, subjectKey = "T-4"),
            )
            val conflictId = pullConflict(stack, "T-4", "Torque to 52 Nm.")

            stack.resolver.dismiss(conflictId, "both are context-dependent")

            val conflict = stack.database.conflictDao().getById(conflictId)
            assertEquals(ConflictResolutionState.DISMISSED.name, conflict?.state)
            assertEquals("Torque to 52 Nm.", conflict?.incomingContent)

            val row = stack.database.memoryDao().getById(local.memoryId)
            assertEquals(false, row?.tombstone)
            assertEquals("Torque to 45 Nm.", row?.content)
        } finally {
            stack.close()
        }
    }

    @Test
    fun resolutionSurvivesRoomAndShardRestart() = runBlocking {
        val dbName = "resolve-restart-${UUID.randomUUID()}"
        val qdrantDir = File(sandbox, "resolve-restart-qdrant")

        val first = newStack(dbName, qdrantDir)
        first.repository.create(
            CreateMemoryInput("Torque", "Torque to 45 Nm.", type = MemoryType.PROCEDURE, subjectKey = "T-5"),
        )
        val conflictId = pullConflict(first, "T-5", "Torque to 52 Nm.")
        first.resolver.keepCloud(conflictId)
        first.close()

        val second = newStack(dbName, qdrantDir)
        try {
            val conflict = second.database.conflictDao().getById(conflictId)
            assertEquals(ConflictResolutionState.RESOLVED_CLOUD.name, conflict?.state)
            val row = second.database.memoryDao().getById(pointId("cloud-t-1"))
            assertEquals("Torque to 52 Nm.", row?.content)
            val hits = second.repository.search("torque 52 Nm", 5)
            assertTrue("resolved cloud knowledge stays retrievable after restart", hits.any { it.memory.memoryId == pointId("cloud-t-1") })
        } finally {
            second.close()
        }
    }

    @Test
    fun resolvingAnAlreadyResolvedConflictIsRejected() = runBlocking {
        val stack = newStack()
        try {
            stack.repository.create(
                CreateMemoryInput("Torque", "Torque to 45 Nm.", type = MemoryType.PROCEDURE, subjectKey = "T-6"),
            )
            val conflictId = pullConflict(stack, "T-6", "Torque to 52 Nm.")
            stack.resolver.dismiss(conflictId)

            var rejected = false
            try {
                stack.resolver.keepLocal(conflictId)
            } catch (expected: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("a resolved conflict cannot be resolved twice", rejected)
        } finally {
            stack.close()
        }
    }

    private suspend fun pullConflict(stack: Stack, subjectKey: String, incomingContent: String): String {
        stack.remote.add(
            CloudKnowledgeBatch(listOf(cloudItem("cloud-t-1", subjectKey, incomingContent, "h52", version = 1))),
        )
        stack.ingestor.pullAndApply()
        assertEquals(1L, stack.database.conflictDao().countUnresolved())
        return stack.database.conflictDao().listAll().single().conflictId
    }

    private fun cloudItem(
        memoryId: String,
        subjectKey: String,
        content: String,
        contentHash: String,
        version: Int,
    ) = CloudKnowledgeItem(
        memoryId = pointId(memoryId),
        subjectKey = subjectKey,
        title = content.take(40),
        content = content,
        contentHash = contentHash,
        version = version,
        updatedAt = System.currentTimeMillis(),
        origin = "CLOUD",
        authority = "central-engineering",
        tombstone = false,
        metadata = emptyMap(),
    )

    /** qdrant-edge point ids accept u64/UUID; local repository ids (UUIDs) pass through. */
    private fun pointId(label: String): String {
        val uuid = runCatching { UUID.fromString(label) }.getOrNull()
        return uuid?.toString()
            ?: UUID.nameUUIDFromBytes(label.toByteArray(Charsets.UTF_8)).toString()
    }

    private class Stack(
        val database: EdgeMindDatabase,
        val store: LocalVectorStore,
        val repository: DefaultMemoryRepository,
        val ingestor: DefaultCloudKnowledgeIngestor,
        val resolver: DefaultConflictResolver,
        val remote: ScriptedRemote,
    ) {
        fun close() {
            runBlocking { store.close() }
            database.close()
        }
    }

    private class ScriptedRemote : com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource {
        private val queue = ArrayDeque<CloudKnowledgeBatch>()

        fun add(batch: CloudKnowledgeBatch) {
            queue.add(batch)
        }

        override suspend fun pullKnowledge(cursor: String?): CloudKnowledgeBatch =
            queue.removeFirstOrNull() ?: CloudKnowledgeBatch(emptyList())
    }

    private fun newStack(
        dbName: String = "resolver-${UUID.randomUUID()}",
        qdrantDir: File = File(sandbox, "qdrant-${UUID.randomUUID()}"),
        embedding: EmbeddingService = FeatureHashingEmbeddingService(),
    ): Stack {
        val database = Room.databaseBuilder(context, EdgeMindDatabase::class.java, dbName).build()
        val store = QdrantEdgeVectorStore(qdrantDir)
        val repository = DefaultMemoryRepository(database.memoryDao(), store, embedding)
        val remote = ScriptedRemote()
        val writer = DefaultCloudKnowledgeWriter(database.memoryDao(), database, store, embedding)
        val ingestor = DefaultCloudKnowledgeIngestor(
            remote = remote,
            classifier = DefaultKnowledgeClassifier(),
            writer = writer,
            memoryDao = database.memoryDao(),
            conflictDao = database.conflictDao(),
            cursorDao = database.cloudCursorDao(),
        )
        val resolver = DefaultConflictResolver(
            conflictDao = database.conflictDao(),
            memoryDao = database.memoryDao(),
            database = database,
            embeddingService = embedding,
            vectorStore = store,
        )
        return Stack(database, store, repository, ingestor, resolver, remote)
    }
}