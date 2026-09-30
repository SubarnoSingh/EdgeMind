package com.example.EdgeMemo.data.seed

import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.ai.llm.ExtractiveLLMService
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.rag.AnswerStatus
import com.example.EdgeMemo.core.rag.RagRequest
import com.example.EdgeMemo.core.rag.RagResponse
import com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore
import com.example.EdgeMemo.data.local.sync.DefaultQdrantSyncEngine
import com.example.EdgeMemo.data.local.sync.QdrantChangeDetector
import com.example.EdgeMemo.data.local.sync.QdrantSyncOperationStore
import com.example.EdgeMemo.data.policy.DefaultPolicyEngine
import com.example.EdgeMemo.data.policy.DefaultRedactionService
import com.example.EdgeMemo.data.repository.QdrantRecordMemoryRepository
import com.example.EdgeMemo.data.retrieval.QdrantRecordRetrievalService
import com.example.EdgeMemo.data.sync.UnimplementedQdrantSyncRemote
import com.example.EdgeMemo.domain.rag.DefaultRagService
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 7 manual test questions from `cubical dataset.pdf`, asked against the
 * seeded dataset through real Qdrant Edge + hybrid retrieval + extractive RAG.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CubicalDatasetAskTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() = TestNativeLoader.ensureLoaded()
    }

    private val dir = File.createTempFile("cubical-", "").apply { delete(); mkdirs() }
    private val store = QdrantEdgeRecordStore(File(dir, "qdrant_sync_store"))
    private val embeddings = FeatureHashingEmbeddingService()
    private val operations = QdrantSyncOperationStore(store)
    private val repository = QdrantRecordMemoryRepository(
        recordStore = store,
        syncEngine = DefaultQdrantSyncEngine(
            recordStore = store,
            operationStore = operations,
            detector = QdrantChangeDetector(store, operations),
            remote = UnimplementedQdrantSyncRemote(),
            cloudKnowledge = object : com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource {
                override suspend fun pullKnowledge(cursor: String?) =
                    throw EdgeError.CloudUnavailable("offline dataset test")
            },
        ),
        embeddingService = embeddings,
        policyEngine = DefaultPolicyEngine(DefaultRedactionService()),
    )
    private val rag = DefaultRagService(
        retrievalService = QdrantRecordRetrievalService(embeddings, store),
        llmService = ExtractiveLLMService(),
    )

    @Before
    fun seed() = runBlocking {
        assertEquals(8, repository.createAll(CubicalDataset.inputs).size)
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { store.close() } }
        dir.deleteRecursively()
    }

    private fun ask(question: String): RagResponse = runBlocking {
        rag.answer(RagRequest(question = question)).also { r ->
            println("\nQ: $question\nstatus=${r.status}\nA: ${r.answer}\nsources=${r.sources.map { it.title }}")
        }
    }

    private fun RagResponse.cites(vararg titles: String) = titles.count { t -> sources.any { it.title == t } }

    @Test
    fun test1_basicRetrievalCitesSealHistoryWithoutConfirmedRootCause() {
        val r = ask("Why does P-101 keep experiencing mechanical seal failures?")
        assertEquals(AnswerStatus.ANSWERED, r.status)
        assertTrue(
            r.cites("Mechanical Seal Leakage", "Mechanical Seal Replacement",
                "Repeated Mechanical Seal Failure", "Seal Failure Investigation") >= 2,
        )
    }

    @Test
    fun test2_procedureRetrieval() {
        val r = ask("What should a technician inspect before replacing the seal again?")
        assertEquals(AnswerStatus.ANSWERED, r.status)
        assertTrue(r.cites("P-101 Mechanical Seal Inspection Procedure") == 1)
    }

    @Test
    fun test3_cavitationEvidence() {
        val r = ask("What evidence suggests possible cavitation on P-101?")
        assertEquals(AnswerStatus.ANSWERED, r.status)
        assertTrue(r.cites("Possible Cavitation During Operation") == 1)
    }

    @Test
    fun test4_exactIdentifier() {
        val r = ask("What incident was associated with the repeated seal failure?")
        assertTrue("INC-1042 must be retrievable", r.answer.contains("INC-1042"))
    }

    @Test
    fun test5_timeline() {
        val r = ask("What happened to P-101 between July 14 and August 28?")
        assertEquals(AnswerStatus.ANSWERED, r.status)
    }

    @Test
    fun test6_noInventedRootCause() {
        val r = ask("What is the confirmed root cause of the P-101 seal failures?")
        val a = r.answer.lowercase()
        assertTrue(
            "answer must state the root cause is not confirmed",
            r.status == AnswerStatus.INSUFFICIENT_EVIDENCE ||
                listOf("unconfirmed", "not conclusively", "insufficient").any { it in a },
        )
    }

    @Test
    fun test7_exactMeasurement() {
        val r = ask("What suction pressure was recorded during the P-101 observations?")
        assertTrue(r.answer.contains("2.1 bar"))
    }
}
