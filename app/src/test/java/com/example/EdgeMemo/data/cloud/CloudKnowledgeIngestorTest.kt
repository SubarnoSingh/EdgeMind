package com.example.EdgeMemo.data.cloud

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.ai.embedding.EmbeddingService
import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.repository.DefaultMemoryRepository
import com.example.EdgeMemo.data.retrieval.DefaultRetrievalService
import com.example.EdgeMemo.data.retrieval.KeywordRetriever
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeBatch
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeClassifier
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeWriter
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cloud → edge ingestion integration: real Room, real qdrant-edge .so, real
 * deterministic embedding, deterministic fixture remotes (never fake cloud —
 * fixtures are explicit test doubles of the CloudKnowledgeRemoteDataSource).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudKnowledgeIngestorTest {

    private lateinit var context: Context
    private lateinit var sandbox: File

    @Before
    fun setUp() {
        TestNativeLoader.ensureLoaded()
        context = ApplicationProvider.getApplicationContext()
        sandbox = File(context.cacheDir, "phase7-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    @Test
    fun newCloudKnowledgeIsAppliedAndSearchableOffline() = runBlocking {
        val stack = newStack()
        try {
            stack.remote.script(
                CloudKnowledgeBatch(
                    items = listOf(item("cloud-1", "TORQUE-1", "Torque the cap screws to 52 Nm.", "h-52", version = 5, authority = "central-engineering")),
                    nextCursor = null,
                ),
            )
            val result = stack.ingestor.pullAndApply()
            assertTrue(result.changed)
            assertEquals(1, result.applied)

            val row = stack.database.memoryDao().getById(pointId("cloud-1"))
            assertEquals("CLOUD", row?.origin)
            assertEquals(MemoryType.CLOUD_KNOWLEDGE.name, row?.type)
            assertEquals(MemorySyncState.SYNCED.name, row?.syncState)
            assertEquals(SyncDecision.SYNC.name, row?.syncDecision)
            assertEquals("central-engineering", row?.authority)
            assertEquals(5, row?.version)

            val hits = stack.repository.search("torque cap screws 52 Nm", limit = 5)
            assertTrue(hits.isNotEmpty())
            assertEquals(pointId("cloud-1"), hits.first().memory.memoryId)
            assertEquals(MemoryOrigin.CLOUD, hits.first().memory.origin)

            // cloud knowledge never enters the outbox
            assertEquals(0L, stack.database.syncOutboxDao().countAll())
        } finally {
            stack.close()
        }
    }

    @Test
    fun duplicateContentIsSkippedWithoutSecondVector() = runBlocking {
        val stack = newStack()
        try {
            val batch = CloudKnowledgeBatch(listOf(item("cloud-1", null, "content", "h-same", version = 1)))
            stack.remote.script(batch)
            stack.ingestor.pullAndApply()
            stack.remote.script(batch)
            val second = stack.ingestor.pullAndApply()
            assertEquals(1, second.duplicates)
            assertEquals(0, second.applied)
            stack.store.ensureReady(512)
            assertEquals(1L, stack.store.count())
        } finally {
            stack.close()
        }
    }

    @Test
    fun newerCloudVersionUpdatesInPlaceWithNoDuplicateVector() = runBlocking {
        val stack = newStack()
        try {
            stack.remote.script(CloudKnowledgeBatch(listOf(item("cloud-1", "TORQUE-1", "Torque to 45 Nm.", "h-45", version = 3))))
            stack.ingestor.pullAndApply()
            stack.remote.script(CloudKnowledgeBatch(listOf(item("cloud-1", "TORQUE-1", "Torque to 52 Nm.", "h-52", version = 4))))
            val result = stack.ingestor.pullAndApply()
            assertEquals(1, result.applied)
            val row = stack.database.memoryDao().getById(pointId("cloud-1"))
            assertEquals("Torque to 52 Nm.", row?.content)
            assertEquals(4, row?.version)
            assertEquals("h-52", row?.contentHash)
            stack.store.ensureReady(512)
            assertEquals("upsert replaced the point, no duplicate vector", 1L, stack.store.count())
        } finally {
            stack.close()
        }
    }

    @Test
    fun supersedeMakesTargetExcludedFromActiveRetrieval() = runBlocking {
        val stack = newStack()
        try {
            stack.remote.script(CloudKnowledgeBatch(listOf(item("proc-1", "P-101", "revision 3", "h-r3", version = 3))))
            stack.ingestor.pullAndApply()
            stack.remote.script(
                CloudKnowledgeBatch(listOf(item("proc-2", "P-101", "revision 4", "h-r4", version = 4, supersedes = "proc-1"))),
            )
            val result = stack.ingestor.pullAndApply()
            assertEquals(1, result.applied)
            assertEquals(listOf(pointId("proc-1")), stack.database.memoryDao().supersededIds())

            // the real retrieval pipeline excludes superseded ids; the superseding
            // record is active and available offline
            val retrieval = DefaultRetrievalService(
                stack.embedding,
                stack.store,
                stack.database.memoryDao(),
                KeywordRetriever(stack.database.memoryDao()),
            )
            val hits = retrieval.retrieve(com.example.EdgeMemo.core.retrieval.RetrievalQuery("revision four pump procedure", limit = 5))
            assertTrue("superseding record is searchable", hits.evidence.any { it.memory.memoryId == pointId("proc-2") })
            assertFalse("superseded record is excluded", hits.evidence.any { it.memory.memoryId == pointId("proc-1") })
        } finally {
            stack.close()
        }
    }

    @Test
    fun cloudTombstoneRemovesActiveCloudRecordFromRetrieval() = runBlocking {
        val stack = newStack()
        try {
            stack.remote.script(CloudKnowledgeBatch(listOf(item("r1", null, "obsolete", "h-1", version = 1))))
            stack.ingestor.pullAndApply()
            stack.remote.script(CloudKnowledgeBatch(listOf(item("r1", null, "obsolete", "h-1", version = 2, tombstone = true))))
            val result = stack.ingestor.pullAndApply()
            assertEquals(1, result.tombstoned)
            val row = stack.database.memoryDao().getById(pointId("r1"))
            assertEquals(true, row?.tombstone)
            stack.store.ensureReady(512)
            assertEquals("tombstoned record no longer serves vectors", 0L, stack.store.count())
            assertTrue(stack.repository.search("obsolete", 5).isEmpty())
        } finally {
            stack.close()
        }
    }

    @Test
    fun divergenceCreatesPersistedConflictWithoutOverwritingLocal() = runBlocking {
        val stack = newStack()
        try {
            val local = stack.repository.create(
                CreateMemoryInput(
                    title = "Torque spec",
                    content = "Torque the cap screws to 45 Nm.",
                    type = MemoryType.PROCEDURE,
                    subjectKey = "P-101-TORQUE",
                ),
            )
            stack.remote.script(
                CloudKnowledgeBatch(listOf(item("cloud-t", "P-101-TORQUE", "Torque the cap screws to 52 Nm.", "h-52", version = 1))),
            )
            val result = stack.ingestor.pullAndApply()
            assertEquals(1, result.conflicts)
            assertEquals(1L, stack.database.conflictDao().countUnresolved())
            val conflict = stack.database.conflictDao().listAll().single()
            assertEquals(local.memoryId, conflict.localMemoryId)
            assertEquals(local.content, conflict.localContent)
            assertEquals("Torque the cap screws to 52 Nm.", conflict.incomingContent)
            assertEquals("LOCAL", conflict.localOrigin)
            assertEquals("CLOUD", conflict.incomingOrigin)
            assertEquals(ConflictResolutionState.UNRESOLVED.name, conflict.state)

            // local record is untouched and still active
            val still = stack.repository.get(local.memoryId)
            assertEquals(local.content, still?.content)
            assertTrue(stack.repository.search("torque 45 Nm", 5).isNotEmpty())
        } finally {
            stack.close()
        }
    }

    @Test
    fun repullingSameContradictionDoesNotDuplicateConflict() = runBlocking {
        val stack = newStack()
        try {
            stack.repository.create(
                CreateMemoryInput("Torque spec", "Torque to 45 Nm.", type = MemoryType.PROCEDURE, subjectKey = "T-1"),
            )
            val batch = CloudKnowledgeBatch(listOf(item("cloud-t", "T-1", "Torque to 52 Nm.", "h-52")))
            stack.remote.script(batch)
            stack.ingestor.pullAndApply()
            stack.remote.script(batch)
            stack.ingestor.pullAndApply()
            assertEquals(1L, stack.database.conflictDao().countUnresolved())
        } finally {
            stack.close()
        }
    }

    @Test
    fun localOnlyIsNeverOverwrittenByCloud() = runBlocking {
        val stack = newStack()
        try {
            val local = stack.repository.create(
                CreateMemoryInput(title = "Gate code", content = "Gate code 4412 for site 7."),
            )
            stack.remote.script(
                CloudKnowledgeBatch(listOf(item(local.memoryId, null, "Gate code 9999.", "h-9999", version = 9))),
            )
            val result = stack.ingestor.pullAndApply()
            assertEquals(1, result.conflicts)
            val still = stack.repository.get(local.memoryId)
            assertEquals("Gate code 4412 for site 7.", still?.content)
            assertEquals("Gate code", still?.title)
        } finally {
            stack.close()
        }
    }

    @Test
    fun mixedBatchNeverProducesOutboxOperations() = runBlocking {
        val stack = newStack()
        try {
            stack.repository.create(
                CreateMemoryInput("Torque", "Torque to 45 Nm.", type = MemoryType.PROCEDURE, subjectKey = "T-2"),
            )
            stack.remote.script(
                CloudKnowledgeBatch(
                    items = listOf(
                        item("n1", null, "brand new", "h-n1"),
                        item("n2", null, "another", "h-n2"),
                        item("cloud-t", "T-2", "Torque to 52 Nm.", "h-52"),
                    ),
                ),
            )
            stack.ingestor.pullAndApply()
            assertEquals("accepted, updated and conflicted cloud knowledge is never pushed back", 0L, stack.database.syncOutboxDao().countAll())
        } finally {
            stack.close()
        }
    }

    @Test
    fun cursorCheckpointResumesIncrementalPull() = runBlocking {
        val stack = newStack()
        try {
            stack.remote.script(CloudKnowledgeBatch(listOf(item("a1", null, "first page", "h-a1")), nextCursor = "cursor-1"))
            stack.ingestor.pullAndApply()
            assertEquals("cursor-1", stack.database.cloudCursorDao().get()?.cursor)

            stack.remote.script(CloudKnowledgeBatch(listOf(item("b1", null, "second page", "h-b1")), nextCursor = null))
            val result = stack.ingestor.pullAndApply()
            assertEquals(1, result.applied)
            assertEquals(listOf(null, "cursor-1"), stack.remote.requestedCursors)
            assertNull(stack.database.cloudCursorDao().get()?.cursor)
        } finally {
            stack.close()
        }
    }

    @Test
    fun acceptedCloudKnowledgeSurvivesRoomAndShardRestart() = runBlocking {
        val dbName = "cloud-restart-${UUID.randomUUID()}"
        val qdrantDir = File(sandbox, "cloud-restart-qdrant")

        val first = newStack(dbName = dbName, qdrantDir = qdrantDir)
        first.remote.script(CloudKnowledgeBatch(listOf(item("persist-1", null, "survives restart", "h-p"))))
        first.ingestor.pullAndApply()
        first.close()

        val second = newStack(dbName = dbName, qdrantDir = qdrantDir)
        try {
            val hits = second.repository.search("survives restart", 5)
            assertTrue(hits.isNotEmpty())
            assertEquals(pointId("persist-1"), hits.first().memory.memoryId)
            assertEquals("CLOUD", second.database.memoryDao().getById(pointId("persist-1"))?.origin)
        } finally {
            second.close()
        }
    }

    @Test
    fun conflictSurvivesRoomAndShardRestart() = runBlocking {
        val dbName = "conflict-restart-${UUID.randomUUID()}"
        val qdrantDir = File(sandbox, "conflict-restart-qdrant")

        val first = newStack(dbName = dbName, qdrantDir = qdrantDir)
        first.repository.create(
            CreateMemoryInput("Torque", "Torque to 45 Nm.", type = MemoryType.PROCEDURE, subjectKey = "T-3"),
        )
        first.remote.script(CloudKnowledgeBatch(listOf(item("cloud-t", "T-3", "Torque to 52 Nm.", "h-52"))))
        first.ingestor.pullAndApply()
        val conflictId = first.database.conflictDao().listAll().single().conflictId
        first.close()

        val second = newStack(dbName = dbName, qdrantDir = qdrantDir)
        try {
            val conflict = second.database.conflictDao().getById(conflictId)
            assertEquals(ConflictResolutionState.UNRESOLVED.name, conflict?.state)
            assertEquals("Torque to 52 Nm.", conflict?.incomingContent)
        } finally {
            second.close()
        }
    }

    private class Stack(
        val database: EdgeMindDatabase,
        val store: LocalVectorStore,
        val repository: DefaultMemoryRepository,
        val ingestor: DefaultCloudKnowledgeIngestor,
        val remote: ScriptedRemote,
        val embedding: EmbeddingService,
    ) {
        fun close() {
            runBlocking { store.close() }
            database.close()
        }
    }

    private class ScriptedRemote : CloudKnowledgeRemoteDataSource {
        private val queue = ArrayDeque<CloudKnowledgeBatch>()
        val requestedCursors = mutableListOf<String?>()

        fun script(batch: CloudKnowledgeBatch): ScriptedRemote {
            queue.add(batch)
            return this
        }

        override suspend fun pullKnowledge(cursor: String?): CloudKnowledgeBatch {
            requestedCursors += cursor
            return queue.removeFirstOrNull() ?: CloudKnowledgeBatch(emptyList())
        }
    }

    private fun newStack(
        dbName: String = "phase7-${UUID.randomUUID()}",
        qdrantDir: File = File(sandbox, "qdrant-${UUID.randomUUID()}"),
        embedding: EmbeddingService = FeatureHashingEmbeddingService(),
    ): Stack {
        val database = Room.databaseBuilder(context, EdgeMindDatabase::class.java, dbName).build()
        val store = QdrantEdgeVectorStore(qdrantDir)
        val repository = DefaultMemoryRepository(database.memoryDao(), store, embedding)
        val remote = ScriptedRemote()
        val classifier: CloudKnowledgeClassifier = DefaultKnowledgeClassifier()
        val writer: CloudKnowledgeWriter = DefaultCloudKnowledgeWriter(database.memoryDao(), database, store, embedding)
        val ingestor = DefaultCloudKnowledgeIngestor(
            remote = remote,
            classifier = classifier,
            writer = writer,
            memoryDao = database.memoryDao(),
            conflictDao = database.conflictDao(),
            cursorDao = database.cloudCursorDao(),
        )
        return Stack(database, store, repository, ingestor, remote, embedding)
    }

    private fun item(
        memoryId: String,
        subjectKey: String?,
        content: String,
        contentHash: String,
        version: Int = 1,
        supersedes: String? = null,
        tombstone: Boolean = false,
        authority: String? = null,
    ) = CloudKnowledgeItem(
        memoryId = pointId(memoryId),
        subjectKey = subjectKey,
        title = content.take(40),
        content = content,
        contentHash = contentHash,
        version = version,
        updatedAt = System.currentTimeMillis(),
        origin = "CLOUD",
        authority = authority,
        supersedes = supersedes?.let(::pointId),
        tombstone = tombstone,
        metadata = emptyMap(),
    )

    /**
     * qdrant-edge point ids accept only u64 or UUID (Phase 1 verified fact), and
     * retrieval resolves point ids back to Room rows by memoryId — so memoryIds
     * must be valid point ids. Test labels become stable version-3 UUIDs, and a
     * real generated UUID (e.g. a local repository id) is used as-is.
     */
    private fun pointId(label: String): String {
        val uuid = runCatching { UUID.fromString(label) }.getOrNull()
        return uuid?.toString() ?: cloudId(label)
    }

    private fun cloudId(label: String): String =
        UUID.nameUUIDFromBytes(label.toByteArray(Charsets.UTF_8)).toString()
}