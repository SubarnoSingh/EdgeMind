package com.example.EdgeMemo.domain.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.ai.llm.ExtractiveLLMService
import com.example.EdgeMemo.ai.llm.LLMService
import com.example.EdgeMemo.ai.llm.LlmRequest
import com.example.EdgeMemo.ai.llm.LlmResponse
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.rag.AnswerStatus
import com.example.EdgeMemo.core.rag.RagError
import com.example.EdgeMemo.core.rag.RagRequest
import com.example.EdgeMemo.core.rag.RagResponse
import com.example.EdgeMemo.core.rag.RagStage
import com.example.EdgeMemo.core.retrieval.RetrievalQuery
import com.example.EdgeMemo.core.retrieval.RetrievalResult
import com.example.EdgeMemo.core.retrieval.RetrievalService
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.repository.DefaultMemoryRepository
import com.example.EdgeMemo.data.retrieval.DefaultRetrievalService
import com.example.EdgeMemo.data.retrieval.KeywordRetriever
import com.example.EdgeMemo.native.qdrant.LocalVectorStore
import com.example.EdgeMemo.native.qdrant.QdrantEdgeVectorStore
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 4 RAG integration tests over real local storage; no retrieval or LLM
 * mocks on the happy path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DefaultRagServiceTest {

    private lateinit var context: Context
    private lateinit var sandbox: File

    @Before
    fun setUp() {
        TestNativeLoader.ensureLoaded()
        context = ApplicationProvider.getApplicationContext()
        sandbox = File(context.cacheDir, "phase4-rag-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    @Test
    fun groundedAnswerWithRealCitationsAndSources() = runBlocking {
        val stack = newStack()
        try {
            val created = stack.repository.create(
                CreateMemoryInput(
                    title = "P-101 repair",
                    content = "The P-101 seal failed because of cavitation. The seal was replaced during the outage. The pump was aligned afterwards.",
                    type = MemoryType.REPAIR,
                ),
            )

            val stages = mutableListOf<RagStage>()
            val response = stack.rag.answer(
                RagRequest(question = "What happened to the P-101 seal?"),
                onStage = { stages.add(it) },
            )

            assertEquals(AnswerStatus.ANSWERED, response.status)
            assertEquals(listOf(RagStage.RETRIEVING, RagStage.GENERATING), stages)
            assertTrue(response.answer.isNotBlank())
            assertTrue(response.sources.isNotEmpty())
            assertTrue(response.evidence.isNotEmpty())

            // every citation maps to a real stored memory
            val evidenceIds = response.evidence.map { it.memory.memoryId }.toSet()
            assertTrue(evidenceIds.contains(created.memoryId))
            assertTrue(response.sources.all { it.memoryId in evidenceIds })
            assertTrue(response.sources.all { it.index == response.evidence.first { e -> e.memory.memoryId == it.memoryId }.rank })

            // every cited sentence is present verbatim in the evidence
            for (segment in response.answer.split(Regex("\\s*\\[\\d+]\\s*"))) {
                if (segment.isBlank()) continue
                assertTrue(
                    "sentence must be verbatim evidence: $segment",
                    response.evidence.any { it.memory.content.contains(segment) },
                )
            }
        } finally {
            stack.close()
        }
    }

    @Test
    fun insufficientEvidenceIsExplicitNotFabricated() = runBlocking {
        val stack = newStack()
        try {
            stack.repository.create(
                CreateMemoryInput(
                    title = "Golf notes",
                    content = "The fairway was wet after the morning rain. Practice putting for twenty minutes.",
                    type = MemoryType.OBSERVATION,
                ),
            )

            val response = stack.rag.answer(
                RagRequest(question = "What is the planetary gearbox torque specification?"),
            )

            assertEquals(AnswerStatus.INSUFFICIENT_EVIDENCE, response.status)
            assertEquals(DefaultRagService.INSUFFICIENT_MESSAGE, response.answer)
        } finally {
            stack.close()
        }
    }

    @Test
    fun emptyQuestionIsAnErrorWithoutRetrieval() = runBlocking {
        val stack = newStack()
        try {
            val response = stack.rag.answer(RagRequest(question = "   "))
            assertEquals(AnswerStatus.ERROR, response.status)
            assertTrue(response.error is RagError.EmptyQuestion)
            assertEquals(emptyList<Any>(), response.evidence)
        } finally {
            stack.close()
        }
    }

    @Test
    fun retrievalFailureSurfacesAsError() = runBlocking {
        val stack = newStack(failingRetrieval = true)
        try {
            val response = stack.rag.answer(RagRequest(question = "anything"))
            assertEquals(AnswerStatus.ERROR, response.status)
            assertTrue(response.error is RagError.RetrievalFailed)
        } finally {
            stack.close()
        }
    }

    @Test
    fun generationFailureSurfacesAsErrorButKeepsEvidence() = runBlocking {
        val stack = newStack(llm = ExplodingLlm())
        try {
            stack.repository.create(
                CreateMemoryInput(
                    title = "Coupler spec",
                    content = "Replace the coupling once a year. Measure the runout with a dial indicator.",
                    type = MemoryType.PROCEDURE,
                ),
            )
            val response = stack.rag.answer(RagRequest(question = "coupler replacement schedule"))
            assertEquals(AnswerStatus.ERROR, response.status)
            assertTrue(response.error is RagError.GenerationFailed)
            assertTrue(response.evidence.isNotEmpty())
            assertTrue(response.sources.isNotEmpty())
        } finally {
            stack.close()
        }
    }

    private class Stack(
        val database: EdgeMindDatabase,
        val store: LocalVectorStore,
        val repository: DefaultMemoryRepository,
        val rag: DefaultRagService,
    ) {
        fun close() {
            runBlocking { store.close() }
            database.close()
        }
    }

    private fun newStack(
        llm: LLMService = ExtractiveLLMService(),
        failingRetrieval: Boolean = false,
    ): Stack {
        val database = Room.databaseBuilder(context, EdgeMindDatabase::class.java, "phase4-${UUID.randomUUID()}").build()
        val store = QdrantEdgeVectorStore(File(sandbox, "qdrant-${UUID.randomUUID()}"))
        val repository = DefaultMemoryRepository(database.memoryDao(), store, com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService())
        val retrieval: RetrievalService = if (failingRetrieval) {
            FailingRetrieval()
        } else {
            DefaultRetrievalService(
                embeddingService = com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService(),
                vectorStore = store,
                dao = database.memoryDao(),
                keywordRetriever = KeywordRetriever(database.memoryDao()),
            )
        }
        return Stack(
            database = database,
            store = store,
            repository = repository,
            rag = DefaultRagService(retrieval, llm),
        )
    }

    private class FailingRetrieval : RetrievalService {
        override suspend fun retrieve(query: RetrievalQuery): RetrievalResult =
            throw RuntimeException("simulated storage outage")
    }

    private class ExplodingLlm : LLMService {
        override val name: String = "exploding"
        override suspend fun answer(request: LlmRequest): LlmResponse =
            throw RuntimeException("simulated inference failure")
    }
}