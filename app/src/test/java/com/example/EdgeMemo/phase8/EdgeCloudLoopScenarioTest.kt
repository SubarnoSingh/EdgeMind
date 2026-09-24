package com.example.EdgeMemo.phase8

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.ai.llm.ExtractiveLLMService
import com.example.EdgeMemo.core.connectivity.ConnectivityMonitor
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.rag.AnswerStatus
import com.example.EdgeMemo.core.rag.CloudEscalation
import com.example.EdgeMemo.core.rag.RagRequest
import com.example.EdgeMemo.data.cloud.DefaultCloudAnswerCache
import com.example.EdgeMemo.data.cloud.DefaultCloudKnowledgeWriter
import com.example.EdgeMemo.data.cloud.DefaultKnowledgeClassifier
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.repository.DefaultMemoryRepository
import com.example.EdgeMemo.data.retrieval.DefaultRetrievalService
import com.example.EdgeMemo.data.retrieval.KeywordRetriever
import com.example.EdgeMemo.domain.cloud.CacheCloudAnswerResult
import com.example.EdgeMemo.domain.cloud.CloudAnswer
import com.example.EdgeMemo.domain.cloud.CloudAnswerDataSource
import com.example.EdgeMemo.domain.rag.DefaultRagService
import com.example.EdgeMemo.domain.rag.EscalatingRagService
import com.example.EdgeMemo.domain.rag.RagService
import com.example.EdgeMemo.native.qdrant.LocalVectorStore
import com.example.EdgeMemo.native.qdrant.QdrantEdgeVectorStore
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 8 end-to-end scenario suite: LOCAL → RAG → (insufficient) → ESCALATION
 * → (explicit) SAVE → OFFLINE retrieval → conflict safety. Real Room, real
 * qdrant-edge .so, real deterministic embedding, fixture cloud only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EdgeCloudLoopScenarioTest {

    private lateinit var context: Context
    private lateinit var sandbox: File

    @Before
    fun setUp() {
        TestNativeLoader.ensureLoaded()
        context = ApplicationProvider.getApplicationContext()
        sandbox = File(context.cacheDir, "phase8-loop-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    @Test
    fun scenario1_localMemoryAnswersWithoutCloudEscalation() = runBlocking {
        val stack = newStack(online = true)
        try {
            stack.repository.create(
                CreateMemoryInput(
                    title = "P-101 torque",
                    content = "The P-101 cap screws must be torqued to 45 Nm. Apply 45 Nm torque to P-101.",
                    type = MemoryType.PROCEDURE,
                ),
            )

            val response = stack.rag.answer(
                RagRequest(question = "What torque should I use for P-101?"),
            )

            assertEquals(AnswerStatus.ANSWERED, response.status)
            assertNull("local answer must not carry a cloud escalation outcome", response.escalation)
            assertTrue(response.sources.isNotEmpty())
            assertEquals("cloud must not be consulted when local evidence suffices", 0, stack.cloud.asked.size)
        } finally {
            stack.close()
        }
    }

    @Test
    fun scenario2_insufficiencyEscalatesToAttributedCloudAnswerWithoutStoring() = runBlocking {
        val stack = newStack(online = true)
        try {
            stack.repository.create(
                CreateMemoryInput(
                    title = "P-101 torque",
                    content = "The P-101 cap screws must be torqued to 45 Nm.",
                    type = MemoryType.PROCEDURE,
                ),
            )

            val response = stack.rag.answer(
                RagRequest(question = "What is the latest approved procedure revision?"),
            )

            assertEquals(AnswerStatus.ANSWERED, response.status)
            val escalated = response.escalation
            assertTrue("expected attributed cloud escalation", escalated is CloudEscalation.Answered)
            assertEquals("central-engineering", (escalated as CloudEscalation.Answered).authority)
            assertEquals("only the question was sent", 1, stack.cloud.asked.size)
            assertTrue("cloud answer is not local evidence", response.evidence.isEmpty())

            // provenance: NOT automatically persisted
            assertEquals(1L, stack.database.memoryDao().count())
            assertEquals("cloud-origin", 0, stack.repository.list().count { it.origin == MemoryOrigin.CLOUD })
            assertEquals(0L, stack.database.syncOutboxDao().countAll())
        } finally {
            stack.close()
        }
    }

    @Test
    fun scenario3_explicitSaveLocalizesCloudKnowledge() = runBlocking {
        val stack = newStack(online = true)
        try {
            val response = stack.rag.answer(RagRequest("What is the latest approved procedure revision?"))
            val escalated = response.escalation as CloudEscalation.Answered

            val saved = stack.cache.save(escalated.question, escalated.answer, escalated.authority)

            assertTrue(saved is CacheCloudAnswerResult.Saved)
            val memory = (saved as CacheCloudAnswerResult.Saved).memory
            assertEquals(MemoryOrigin.CLOUD, memory.origin)
            assertEquals(MemoryType.CLOUD_KNOWLEDGE, memory.type)

            val row = stack.database.memoryDao().getById(memory.memoryId)
            assertEquals("CLOUD", row?.origin)
            assertEquals("SYNCED", row?.syncState)
            assertEquals("central-engineering", row?.authority)
            assertEquals("never enqueued for push-back", 0L, stack.database.syncOutboxDao().countAll())
        } finally {
            stack.close()
        }
    }

    @Test
    fun scenario4_localizedCloudKnowledgeIsRetrievableOffline() = runBlocking {
        val dbName = "phase8-loop-restart-${UUID.randomUUID()}"
        val qdrantDir = File(sandbox, "loop-restart-qdrant")

        val stack = newStack(online = true, dbName = dbName, qdrantDir = qdrantDir)
        stack.repository.create(
            CreateMemoryInput(
                title = "P-101 torque",
                content = "The P-101 cap screws must be torqued to 45 Nm.",
                type = MemoryType.PROCEDURE,
            ),
        )
        val response = stack.rag.answer(RagRequest("What is the latest approved procedure revision?"))
        val escalated = response.escalation as CloudEscalation.Answered
        stack.cache.save(escalated.question, escalated.answer, escalated.authority)
        stack.close()

        val offline = newStack(online = false, dbName = dbName, qdrantDir = qdrantDir)
        try {
            val second = offline.rag.answer(RagRequest("What is the latest approved procedure revision?"))
            assertEquals(AnswerStatus.ANSWERED, second.status)
            assertNull("offline answer must come from local memory, not cloud", second.escalation)
            assertTrue("offline answer cites local evidence", second.sources.isNotEmpty())
            assertEquals("cloud must not be consulted offline", 0, offline.cloud.asked.size)
            assertTrue(second.answer.isNotBlank())
        } finally {
            offline.close()
        }
    }

    @Test
    fun scenario5_cloudContradictionIsNeverSilentlyOverwritten() = runBlocking {
        val stack = newStack(online = true)
        try {
            val question = "What torque should I use for P-101?"
            val subject = answerSubjectOf(question)
            val local = stack.repository.create(
                CreateMemoryInput(
                    title = "Torque note",
                    content = "Documented P-101 torque is 40 Nm locally.",
                    subjectKey = subject,
                ),
            )

            val saved = stack.cache.save(
                question = question,
                answer = "Cloud says the P-101 torque is 52 Nm.",
                authority = "cloud-hub",
            )

            assertTrue(saved is CacheCloudAnswerResult.ConflictPrevented)
            val untouched = stack.repository.get(local.memoryId)
            assertEquals("Documented P-101 torque is 40 Nm locally.", untouched?.content)
            assertEquals(1, stack.repository.list().size)
            assertEquals(0L, stack.database.syncOutboxDao().countAll())
            assertEquals(
                "divergent cloud answer is persisted as a resolvable conflict",
                1L,
                stack.database.conflictDao().countUnresolved(),
            )
        } finally {
            stack.close()
        }
    }

    private class Stack(
        val database: EdgeMindDatabase,
        val store: LocalVectorStore,
        val repository: DefaultMemoryRepository,
        val rag: RagService,
        val cache: DefaultCloudAnswerCache,
        val cloud: ScriptedCloud,
    ) {
        fun close() {
            runBlocking { store.close() }
            database.close()
        }
    }

    private fun newStack(
        online: Boolean,
        dbName: String = "phase8-loop-${UUID.randomUUID()}",
        qdrantDir: File = File(sandbox, "loop-qdrant-${UUID.randomUUID()}"),
    ): Stack {
        val embedding = FeatureHashingEmbeddingService()
        val database = Room.databaseBuilder(context, EdgeMindDatabase::class.java, dbName).build()
        val store = QdrantEdgeVectorStore(qdrantDir)
        val repository = DefaultMemoryRepository(database.memoryDao(), store, embedding)
        val classifier = DefaultKnowledgeClassifier()
        val writer = DefaultCloudKnowledgeWriter(database.memoryDao(), database, store, embedding)
        val cache = DefaultCloudAnswerCache(classifier, writer, database.memoryDao(), database.conflictDao())
        val cloud = ScriptedCloud()

        val retrievalService = DefaultRetrievalService(
            embeddingService = embedding,
            vectorStore = store,
            dao = database.memoryDao(),
            keywordRetriever = KeywordRetriever(database.memoryDao()),
        )
        val localRag = DefaultRagService(retrievalService, ExtractiveLLMService())
        val rag = EscalatingRagService(localRag, cloud, FixedConnectivity(online))
        return Stack(database, store, repository, rag, cache, cloud)
    }

    private class ScriptedCloud : CloudAnswerDataSource {
        val asked = mutableListOf<String>()

        override suspend fun ask(question: String): CloudAnswer {
            asked += question
            return CloudAnswer(
                question = question,
                answer = "The latest approved procedure revision is P-101 revision 4 by central engineering.",
                authority = "central-engineering",
            )
        }
    }

    private class FixedConnectivity(private val online: Boolean) : ConnectivityMonitor {
        override suspend fun isOnline(): Boolean = online
    }
}

private fun answerSubjectOf(question: String): String {
    val normalized = com.example.EdgeMemo.core.retrieval.QueryNormalizer.normalize(question)
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalized.toByteArray(Charsets.UTF_8))
    return com.example.EdgeMemo.data.cloud.DefaultCloudAnswerCache.SUBJECT_PREFIX +
        digest.joinToString("") { "%02x".format(it) }
}