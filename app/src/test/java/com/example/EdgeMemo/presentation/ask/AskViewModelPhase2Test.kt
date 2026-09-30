package com.example.EdgeMemo.presentation.ask

import com.example.EdgeMemo.core.rag.AnswerStatus
import com.example.EdgeMemo.core.rag.CloudEscalation
import com.example.EdgeMemo.core.rag.RagError
import com.example.EdgeMemo.core.rag.RagRequest
import com.example.EdgeMemo.core.rag.RagResponse
import com.example.EdgeMemo.core.rag.RagStage
import com.example.EdgeMemo.core.rag.SourceReference
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.domain.rag.AskQuestionUseCase
import com.example.EdgeMemo.domain.rag.RagService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UI Phase 2 — the Ask state machine extensions: asset context, retry and
 * honest provenance, verified against a recording RagService fake (the fake
 * implements the EXISTING domain interface; production keeps the real one).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModelPhase2Test {

    private lateinit var mainDispatcher: TestDispatcher

    @Before
    fun setUp() {
        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class RecordingRag(
        private val respond: (RagRequest) -> RagResponse = { req ->
            RagResponse(
                question = req.question,
                answer = "Seal failures on P-101 trace to cavitation. [1]",
                status = AnswerStatus.ANSWERED,
                sources = listOf(
                    SourceReference(
                        index = 1,
                        memoryId = "m-1",
                        chunkId = null,
                        title = "P-101 seal observation",
                        source = "USER_ENTRY",
                        type = MemoryType.OBSERVATION,
                        page = null,
                        section = null,
                        chunkIndex = null,
                        score = 0.032,
                        snippet = "Seal weep observed on P-101.",
                    ),
                ),
                evidence = emptyList(),
            )
        },
    ) : RagService {
        val requests = mutableListOf<String>()

        override suspend fun answer(request: RagRequest, onStage: (RagStage) -> Unit): RagResponse {
            requests += request.question
            onStage(RagStage.RETRIEVING)
            onStage(RagStage.GENERATING)
            return respond(request)
        }
    }

    private fun advance() = mainDispatcher.scheduler.advanceUntilIdle()

    @Test
    fun assetContextIsAppendedToTheRealQueryAndShownSeparately() {
        val rag = RecordingRag()
        val vm = AskViewModel(AskQuestionUseCase(rag))
        vm.setAssetContext("p101")
        vm.onQuestionChange("Why does the seal keep failing?")
        vm.ask()
        advance()

        val state = vm.uiState.value
        assertEquals(AskPhase.SUCCESS, state.phase)
        assertEquals("Why does the seal keep failing?", state.submittedQuestion)
        assertEquals(
            "Why does the seal keep failing? p101",
            state.executedQuestion,
        )
        assertEquals(listOf("Why does the seal keep failing? p101"), rag.requests)
        assertEquals("p101", state.assetNamespace)
    }

    @Test
    fun assetTokenAlreadyInQueryIsNotDuplicated() {
        val rag = RecordingRag()
        val vm = AskViewModel(AskQuestionUseCase(rag))
        vm.setAssetContext("p101")
        vm.onQuestionChange("why is P-101 failing")
        vm.ask()
        advance()

        assertEquals("why is P-101 failing", vm.uiState.value.executedQuestion)
        assertEquals(listOf("why is P-101 failing"), rag.requests)
    }

    @Test
    fun clearDropsAssetContextForANewQuestion() {
        val rag = RecordingRag()
        val vm = AskViewModel(AskQuestionUseCase(rag))
        vm.setAssetContext("p101")
        vm.clear()
        assertNull(vm.uiState.value.assetNamespace)

        vm.onQuestionChange("vibration limits?")
        vm.ask()
        advance()
        assertEquals("vibration limits?", rag.requests.last())
    }

    @Test
    fun retryReExecutesTheLastQueryVerbatim() {
        var fail = true
        val rag = RecordingRag { req ->
            if (fail) throw RagError.RetrievalFailed("local retrieval failed")
            RagResponse(req.question, "ok", AnswerStatus.ANSWERED, emptyList(), emptyList())
        }
        val vm = AskViewModel(AskQuestionUseCase(rag))
        vm.setAssetContext("line-b")
        vm.onQuestionChange("recent failures?")
        vm.ask()
        advance()
        assertEquals(AskPhase.ERROR, vm.uiState.value.phase)

        fail = false
        vm.retry()
        advance()
        assertEquals(AskPhase.SUCCESS, vm.uiState.value.phase)
        assertEquals(
            listOf("recent failures? line-b", "recent failures? line-b"),
            rag.requests,
        )
        assertTrue(vm.uiState.value.errorMessage == null)
    }

    @Test
    fun provenanceReflectsOnlyRealEscalationStates() {
        // Local answer: no escalation.
        val localRag = RecordingRag()
        val local = AskViewModel(AskQuestionUseCase(localRag))
        local.onQuestionChange("q")
        local.ask()
        advance()
        assertEquals(AskProvenance.LOCAL, local.uiState.value.provenance)

        // Cloud answered.
        val cloudRag = RecordingRag { req ->
            RagResponse(
                question = req.question,
                answer = "cloud says 52 Nm",
                status = AnswerStatus.ANSWERED,
                sources = emptyList(),
                evidence = emptyList(),
                escalation = CloudEscalation.Answered(req.question, "cloud says 52 Nm", "hub-1"),
            )
        }
        val cloud = AskViewModel(AskQuestionUseCase(cloudRag))
        cloud.onQuestionChange("q")
        cloud.ask()
        advance()
        assertEquals(AskProvenance.CLOUD, cloud.uiState.value.provenance)

        // Insufficient local evidence (escalation Offline) stays LOCAL —
        // never presented as an answer, never inferred as cloud usage.
        val insufRag = RecordingRag { req ->
            RagResponse(
                question = req.question,
                answer = "not enough",
                status = AnswerStatus.INSUFFICIENT_EVIDENCE,
                sources = emptyList(),
                evidence = emptyList(),
                escalation = CloudEscalation.Offline,
            )
        }
        val insuf = AskViewModel(AskQuestionUseCase(insufRag))
        insuf.onQuestionChange("q")
        insuf.ask()
        advance()
        assertEquals(AskPhase.INSUFFICIENT, insuf.uiState.value.phase)
        assertEquals(AskProvenance.LOCAL, insuf.uiState.value.provenance)
        assertTrue(insuf.uiState.value.isOffline)
        assertTrue(!insuf.uiState.value.isCloudAnswer)
    }

    @Test
    fun groundedSourceCountComesFromRealCitationsOnly() {
        val rag = RecordingRag()
        val vm = AskViewModel(AskQuestionUseCase(rag))
        vm.onQuestionChange("seal?")
        vm.ask()
        advance()
        assertEquals(1, vm.uiState.value.sources.size)
        assertEquals(1, vm.uiState.value.sources.count { it.memoryId == "m-1" })
        // No fabricated confidence: the model carries raw fused score only.
        assertTrue(vm.uiState.value.sources.first().score in 0.0..1.0)
    }
}
