package com.example.EdgeMemo.domain.rag

import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.ai.llm.ExtractiveLLMService
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.rag.AnswerStatus
import com.example.EdgeMemo.core.rag.RagRequest
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.retrieval.RetrievalQuery
import com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore
import com.example.EdgeMemo.data.local.sync.DefaultQdrantSyncEngine
import com.example.EdgeMemo.data.local.sync.QdrantChangeDetector
import com.example.EdgeMemo.data.local.sync.QdrantSyncOperationStore
import com.example.EdgeMemo.data.policy.DefaultPolicyEngine
import com.example.EdgeMemo.data.policy.DefaultRedactionService
import com.example.EdgeMemo.data.repository.QdrantRecordMemoryRepository
import com.example.EdgeMemo.data.retrieval.QdrantRecordRetrievalService
import com.example.EdgeMemo.data.sync.UnimplementedQdrantSyncRemote
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 13.3 — the single most important integration proof: records written
 * through the production Qdrant-native repository are immediately and
 * durably retrievable by the production retrieval pipeline and consumable by
 * the production RAG service, offline, with real citations. No mocks stand
 * in for Qdrant, embedding, retrieval, or the answer layer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QdrantRetrievalRagEndToEndTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    private val dir = File.createTempFile("p133-e2e-", "").apply { delete(); mkdirs() }
    private fun shardPath() = File(dir, "qdrant_sync_store")
    private val store = QdrantEdgeRecordStore(shardPath())
    private val embeddings = FeatureHashingEmbeddingService()
    private val operations = QdrantSyncOperationStore(store)
    private val engine = DefaultQdrantSyncEngine(
        recordStore = store,
        operationStore = operations,
        detector = QdrantChangeDetector(store, operations),
        remote = UnimplementedQdrantSyncRemote(),
        cloudKnowledge = object : com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource {
            override suspend fun pullKnowledge(cursor: String?) =
                throw EdgeError.CloudUnavailable("e2e is authoring + retrieval only")
        },
    )
    private val repository = QdrantRecordMemoryRepository(
        recordStore = store,
        syncEngine = engine,
        embeddingService = embeddings,
        policyEngine = DefaultPolicyEngine(DefaultRedactionService()),
    )
    private val retrieval = QdrantRecordRetrievalService(embeddings, store)
    private val rag = DefaultRagService(
        retrievalService = retrieval,
        llmService = ExtractiveLLMService(),
    )

    @After
    fun tearDown() {
        runBlocking { runCatching { store.close() } }
        dir.deleteRecursively()
    }

    private fun create(
        title: String,
        content: String,
        type: MemoryType = MemoryType.NOTE,
        choice: SyncDecision? = null,
        metadata: Map<String, String> = emptyMap(),
    ): Memory = runBlocking {
        repository.create(
            CreateMemoryInput(title = title, content = content, type = type,
                userSyncChoice = choice, metadata = metadata),
        )
    }

    // ------------------------------------------------------------------
    // §11 — newly created record: write → shard → retrieval → RAG → citations
    // ------------------------------------------------------------------

    @Test
    fun newlyCreatedRecordIsPersistedRetrievableAndAnswerable() = runBlocking {
        val created = create(
            title = "P-101 seal material",
            content = "The P-101 mechanical seal uses a carbon-vs-silicon-carbide face set.",
            choice = SyncDecision.SYNC,
        )

        // 2. Durable in the application shard as a real vector-bearing point.
        val stored = store.get(RecordId.fromString(created.memoryId))!!
        assertNotNull("record point must live in qdrant_sync_store", stored)
        assertNotNull("embedding must be stored with the record", stored.vector)

        // 3-6. Retrieval finds it densely and by keyword; hybrid ranks it.
        val result = retrieval.retrieve(
            RetrievalQuery(text = "P-101 mechanical seal face material", limit = 5),
        )
        val evidence = result.evidence.first { it.memory.memoryId == created.memoryId }
        assertNotNull("newly created record must be immediately retrievable", evidence)
        assertNotNull(evidence.denseScore)
        assertNotNull("identifier keyword channel must contribute", evidence.keywordScore)

        // 7-8. RAG consumes it and cites it truthfully.
        val response = rag.answer(
            RagRequest(question = "What seal face material does P-101 use?"),
        )
        assertEquals(AnswerStatus.ANSWERED, response.status)
        assertTrue(response.sources.any { it.memoryId == created.memoryId })
        response.sources.forEach { source ->
            assertTrue("citations must reference real retrieved memories",
                result.evidence.any { it.memory.memoryId == source.memoryId })
        }
    }

    // ------------------------------------------------------------------
    // §12 — P-101 industrial scenario: evidence selection, not wording
    // ------------------------------------------------------------------

    @Test
    fun p101ScenarioSurfacesEveryRelevantRecordWithCitations() = runBlocking {
        val maintenance = create(
            "P-101 maintenance record",
            "September inspection: P-101 running 12% above baseline vibration.",
            type = MemoryType.OBSERVATION,
        )
        val repair = create(
            "P-101 repair history",
            "P-101 mechanical seal failed twice this quarter; second failure traced to cavitation upstream.",
            type = MemoryType.REPAIR, // policy: SYNC_REDACTED — storage stays raw locally
        )
        val observation = create(
            "P-101 seal failure observation",
            "Mechanical seal faces on P-101 show heat checking after dry-run events on line A.",
            type = MemoryType.OBSERVATION,
        )
        val procedure = create(
            "P-101 seal maintenance procedure",
            "Verify minimum submergence and flush plan 11 before restarting P-101 to prevent seal failures.",
            type = MemoryType.PROCEDURE,
        )

        val response = rag.answer(
            RagRequest(
                question = "Why does P-101 keep experiencing mechanical seal failures?",
                limit = 5,
            ),
        )

        assertEquals(AnswerStatus.ANSWERED, response.status)
        val citedIds = response.evidence.map { it.memory.memoryId }.toSet()
        for (id in listOf(maintenance.memoryId, repair.memoryId, observation.memoryId, procedure.memoryId)) {
            assertTrue("P-101 evidence must be selected, missing $id", id in citedIds)
        }
        // Every citation references a real stored memory; nothing fabricated.
        response.sources.forEach { source ->
            assertNotNull(store.get(RecordId.fromString(source.memoryId)))
        }
        // Repair content was policy-redacted for SYNC only — the local record
        // used as evidence still holds the raw text.
        val repairEvidence = response.evidence.first { it.memory.memoryId == repair.memoryId }
        assertEquals(SyncDecision.SYNC_REDACTED, repairEvidence.memory.syncDecision)
        assertTrue(repairEvidence.memory.content.contains("cavitation"))
    }

    // ------------------------------------------------------------------
    // §11.9-11 — restart then retrieve again
    // ------------------------------------------------------------------

    @Test
    fun createdRecordsRemainRetrievableAfterShardReopen() = runBlocking {
        val created = create(
            "Bearing temperature log",
            "P-101 drive-end bearing reached 84 C during the July run.",
            choice = SyncDecision.SYNC,
        )
        store.close()

        // Entirely new process-equivalent stack over the same on-disk shard.
        val reopened = QdrantEdgeRecordStore(shardPath())
        try {
            reopened.open()
            val retrieval2 = QdrantRecordRetrievalService(embeddings, reopened)
            val result = retrieval2.retrieve(
                RetrievalQuery(text = "P-101 bearing temperature July", limit = 5),
            )
            assertNotNull(result.evidence.firstOrNull { it.memory.memoryId == created.memoryId })

            val rag2 = DefaultRagService(retrieval2, ExtractiveLLMService())
            val response = rag2.answer(RagRequest("When did the P-101 bearing overheat?"))
            assertEquals(AnswerStatus.ANSWERED, response.status)
        } finally {
            reopened.close()
        }
    }

    // ------------------------------------------------------------------
    // §10/Q — offline: no network collaborators exist on this path at all
    // ------------------------------------------------------------------

    @Test
    fun offlineLocalPathAnswersFromQdrantEvidenceWithoutAnyRemote() = runBlocking {
        create(
            "Line B gearbox",
            "Line B gearbox oil analysis showed elevated water content last sampling.",
        )
        // The rag instance above is DefaultRagService (pure local). Production
        // escalation wraps it; the local-first contract is that sufficient
        // local evidence never consults the cloud — asserted here with the
        // local core that requires no connectivity check to answer.
        val response = rag.answer(RagRequest("What did gearbox oil analysis show on line B?"))
        assertEquals(AnswerStatus.ANSWERED, response.status)
        assertTrue(response.sources.isNotEmpty())
    }
}
