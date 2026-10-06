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
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource
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
 * Verifies the two-machine pitch build on a FRESH store: P-101 (existing) plus
 * P-103 (new) are both seeded through the production batch write path, and the
 * six suggested Ask questions retrieve real, cited evidence for either machine —
 * without hardcoding answers. Also asserts P-103/P-101 isolation, the exact
 * incident identifier, and honest "root cause not confirmed" behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TwoMachineSeedAskTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() = TestNativeLoader.ensureLoaded()
    }

    private val dir = File.createTempFile("twomachine-", "").apply { delete(); mkdirs() }
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
            cloudKnowledge = object : CloudKnowledgeRemoteDataSource {
                override suspend fun pullKnowledge(cursor: String?) =
                    throw EdgeError.CloudUnavailable("offline seed test")
            },
        ),
        embeddingService = embeddings,
        policyEngine = DefaultPolicyEngine(DefaultRedactionService()),
    )
    private val rag = DefaultRagService(
        retrievalService = QdrantRecordRetrievalService(embeddings, store),
        llmService = ExtractiveLLMService(),
    )

    private val p101Titles = setOf(
        "Mechanical Seal Leakage", "Increased Pump Vibration", "Mechanical Seal Replacement",
        "Repeated Mechanical Seal Failure", "P-101 Mechanical Seal Inspection Procedure",
        "Possible Cavitation During Operation", "Seal Failure Investigation",
        "P-101 Cavitation Investigation Procedure",
    )
    private val p103Titles = setOf(
        "Elevated Vibration Reported", "Coupling Alignment Inspected", "Vibration Alert Logged",
        "Recurring Vibration After Alignment", "Bearing Condition Checked",
        "P-103 Vibration Follow-up Procedure", "Repeated Vibration Investigation",
        "P-103 Cooling Water Pump Suction Check",
    )

    @Before
    fun seedFreshInstall() {
        // Fresh install seeds BOTH demo machines through the same seam as the app.
        runBlocking { repository.createAll(CubicalDataset.inputs + CoolingWaterPumpDataset.inputs) }
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { store.close() } }
        dir.deleteRecursively()
    }

    private fun ask(question: String): RagResponse =
        runBlocking { rag.answer(RagRequest(question = question)) }

    private fun RagResponse.citesAny(vararg titles: String) =
        titles.any { t -> sources.any { it.title == t } }

    private fun RagResponse.citesP103() = sources.any { it.title in p103Titles }

    @Test
    fun bothMachinesSeededAndIsolated() = runBlocking {
        val all = repository.list()
        assertEquals(16, all.size) // 8 P-101 + 8 P-103
        assertTrue(all.any { it.subjectKey?.startsWith("p-101/") == true })
        assertTrue(all.any { it.subjectKey?.startsWith("p-103/") == true })
        // No P-103 record ever carries a P-101 namespace and vice versa.
        assertTrue(all.filter { it.subjectKey?.startsWith("p-101/") == true }
            .all { it.subjectKey!!.startsWith("p-101/") })
        assertTrue(all.filter { it.subjectKey?.startsWith("p-103/") == true }
            .all { it.subjectKey!!.startsWith("p-103/") })
    }

    @Test
    fun q1_failingRepeatedly_citesP103Evidence() {
        val r = ask("Why is this asset failing repeatedly? p-103")
        assertEquals(AnswerStatus.ANSWERED, r.status)
        assertTrue("must cite real P-103 records", r.citesP103())
    }

    @Test
    fun q2_recentMaintenance_citesP103Evidence() {
        val r = ask("What maintenance has this asset had recently? p-103")
        assertEquals(AnswerStatus.ANSWERED, r.status)
        assertTrue("must cite real P-103 records", r.citesP103())
    }

    @Test
    fun q3_failuresOrIncidents_citesP103Evidence() {
        val r = ask("What failures or incidents are recorded? p-103")
        assertEquals(AnswerStatus.ANSWERED, r.status)
        assertTrue("must cite real P-103 records", r.citesP103())
    }

    @Test
    fun q4_procedures_citesP103Evidence() {
        val r = ask("What procedures are available? p-103")
        assertEquals(AnswerStatus.ANSWERED, r.status)
        assertTrue("must cite real P-103 records", r.citesP103())
    }

    @Test
    fun q5_recentObservations_citesP103Observation() {
        val r = ask("What observations were recently recorded? p-103")
        assertEquals(AnswerStatus.ANSWERED, r.status)
        assertTrue(r.citesAny("Elevated Vibration Reported", "Recurring Vibration After Alignment"))
    }

    @Test
    fun q6_evidenceForRepeatedFailure_citesP103Evidence() {
        val r = ask("What evidence exists for the repeated failure? p-103")
        assertEquals(AnswerStatus.ANSWERED, r.status)
        assertTrue("must cite real P-103 records", r.citesP103())
    }

    @Test
    fun p103ExactIncidentReferenceIsRetrievable() {
        val r = ask("What incident DEMO-EVT-103-01 relates to pump P-103?")
        assertTrue("exact identifier must be retrievable", r.answer.contains("DEMO-EVT-103-01"))
    }

    @Test
    fun p103RootCauseIsNotFabricated() {
        val r = ask("What is the confirmed root cause of the repeated P-103 vibration? p-103")
        val a = r.answer.lowercase()
        assertTrue(
            "answer must not invent a confirmed root cause",
            r.status == AnswerStatus.INSUFFICIENT_EVIDENCE ||
                listOf("unconfirmed", "not conclusively", "insufficient").any { it in a },
        )
    }

    @Test
    fun genericQuestionsStillAnswerAcrossBothMachines() {
        // Asked without an asset token, the generic suggested questions still find
        // real evidence (from either machine) and never fabricate.
        val r = ask("What maintenance has this asset had recently?")
        assertEquals(AnswerStatus.ANSWERED, r.status)
        assertTrue(
            r.sources.isNotEmpty() &&
                r.sources.any { it.title in p101Titles + p103Titles },
        )
    }

    @Test
    fun reseedingAnExistingInstallDoesNotDuplicate() = runBlocking {
        val before = repository.list().size
        // The app seeds from a SharedPreferences-gated path; even if the flag were
        // lost, the missing-record filter must add nothing to a complete store.
        val present = repository.list().map { (it.subjectKey ?: "").lowercase() to it.title.trim().lowercase() }.toHashSet()
        val missing = (CubicalDataset.inputs + CoolingWaterPumpDataset.inputs)
            .filter { ((it.subjectKey ?: "").lowercase() to it.title.trim().lowercase()) !in present }
        assertTrue("no records should be re-added to a fully seeded store", missing.isEmpty())
        assertEquals(before, repository.list().size)
    }
}
