package com.example.EdgeMemo.presentation.ask

import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.rag.AnswerStatus
import com.example.EdgeMemo.core.rag.CloudEscalation
import com.example.EdgeMemo.core.rag.RagError
import com.example.EdgeMemo.core.rag.RagRequest
import com.example.EdgeMemo.core.rag.RagResponse
import com.example.EdgeMemo.core.rag.RagStage
import com.example.EdgeMemo.core.rag.SourceReference
import com.example.EdgeMemo.domain.cloud.CacheCloudAnswerResult
import com.example.EdgeMemo.domain.cloud.CacheCloudAnswerUseCase
import com.example.EdgeMemo.domain.cloud.CloudAnswerCache
import com.example.EdgeMemo.domain.rag.AskQuestionUseCase
import com.example.EdgeMemo.domain.rag.RagService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModelTest {

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

    @Test
    fun askDrivesStagesToSuccessAndShowsSources() {
        val fake = FakeRag { question -> RagResponse(
            question = question,
            answer = "The P-101 seal failed because of cavitation. [1]",
            status = AnswerStatus.ANSWERED,
            sources = listOf(
                SourceReference(
                    index = 1,
                    memoryId = "m1",
                    chunkId = "doc-1#0",
                    title = "P-101 repair",
                    source = "USER_ENTRY",
                    type = MemoryType.REPAIR,
                    page = null,
                    section = null,
                    chunkIndex = 0,
                    score = 0.9,
                    snippet = "The P-101 seal failed because of cavitation.",
                ),
            ),
            evidence = emptyList(),
        )}
        val viewModel = AskViewModel(AskQuestionUseCase(fake))

        viewModel.onQuestionChange("What happened to the P-101 seal?")
        viewModel.ask()
        advance()

        val state = viewModel.uiState.value
        assertEquals(AskPhase.SUCCESS, state.phase)
        assertTrue(state.answer.contains("[1]"))
        assertEquals(1, state.sources.size)
        assertNull(state.errorMessage)
        assertEquals(listOf(RagStage.RETRIEVING, RagStage.GENERATING), fake.sawStages)
    }

    @Test
    fun askReportsInsufficientEvidence() {
        val fake = FakeRag { question -> RagResponse(
            question = question,
            answer = "Local memory does not contain enough evidence to answer this question.",
            status = AnswerStatus.INSUFFICIENT_EVIDENCE,
            sources = emptyList(),
            evidence = emptyList(),
        )}
        val viewModel = AskViewModel(AskQuestionUseCase(fake))

        viewModel.onQuestionChange("Planetary gearbox torque?")
        viewModel.ask()
        advance()

        val state = viewModel.uiState.value
        assertEquals(AskPhase.INSUFFICIENT, state.phase)
        assertTrue(state.hasResult)
        assertNull(state.errorMessage)
    }

    @Test
    fun askSurfacesErrorsInErrorPhase() {
        val fake = FakeRag { question ->
            throw RagError.RetrievalFailed("local vector store unavailable")
        }
        val viewModel = AskViewModel(AskQuestionUseCase(fake))

        viewModel.onQuestionChange("any question")
        viewModel.ask()
        advance()

        val state = viewModel.uiState.value
        assertEquals(AskPhase.ERROR, state.phase)
        assertEquals("local vector store unavailable", state.errorMessage)
    }

    @Test
    fun blankQuestionDoesNothing() {
        var invoked = false
        val fake = FakeRag { question ->
            invoked = true
            RagResponse(question, "", AnswerStatus.ERROR, emptyList(), emptyList())
        }
        val viewModel = AskViewModel(AskQuestionUseCase(fake))

        viewModel.onQuestionChange("   ")
        viewModel.ask()
        advance()

        assertEquals(AskPhase.IDLE, viewModel.uiState.value.phase)
        assertTrue(!invoked)
    }

    @Test
    fun toggleSourceExpandsAndCollapsesInspector() {
        val fake = FakeRag { question -> RagResponse(question, "answer", AnswerStatus.ANSWERED, emptyList(), emptyList()) }
        val viewModel = AskViewModel(AskQuestionUseCase(fake))

        viewModel.onQuestionChange("question")
        viewModel.ask()
        advance()

        assertNull(viewModel.uiState.value.expandedSourceIndex)
        viewModel.toggleSource(2)
        assertEquals(2, viewModel.uiState.value.expandedSourceIndex)
        viewModel.toggleSource(2)
        assertNull(viewModel.uiState.value.expandedSourceIndex)
    }

    @Test
    fun clearResetsToIdle() {
        val fake = FakeRag { question -> RagResponse(question, "ans", AnswerStatus.ANSWERED, emptyList(), emptyList()) }
        val viewModel = AskViewModel(AskQuestionUseCase(fake))

        viewModel.onQuestionChange("question")
        viewModel.ask()
        advance()
        viewModel.clear()

        val state = viewModel.uiState.value
        assertEquals(AskPhase.IDLE, state.phase)
        assertEquals("", state.question)
        assertEquals("", state.answer)
    }

    @Test
    fun cloudAnsweredEscalationIsShownButNotAutomaticallySaved() {
        val fake = FakeRag { question ->
            RagResponse(
                question = question,
                answer = "The latest approved procedure revision is P-101 revision 4.",
                status = AnswerStatus.ANSWERED,
                sources = emptyList(),
                evidence = emptyList(),
                escalation = CloudEscalation.Answered(question, "The latest approved procedure revision is P-101 revision 4.", "central-engineering"),
            )
        }
        val cache = FakeCache { CacheCloudAnswerResult.AlreadyPresent }
        val viewModel = AskViewModel(AskQuestionUseCase(fake), CacheCloudAnswerUseCase(cache))

        viewModel.onQuestionChange("What is the latest approved procedure revision?")
        viewModel.ask()
        advance()

        val state = viewModel.uiState.value
        assertEquals(AskPhase.SUCCESS, state.phase)
        assertTrue(state.isCloudAnswer)
        assertEquals("central-engineering", (state.escalation as CloudEscalation.Answered).authority)
        assertFalse("cloud answer is never auto-stored", state.savedToMemory)
        assertEquals("no save may happen without the user action", 0, cache.saves)
    }

    @Test
    fun offlineLimitationIsReported() {
        val fake = FakeRag { question -> RagResponse(question, "Local memory does not contain enough evidence.", AnswerStatus.INSUFFICIENT_EVIDENCE, emptyList(), emptyList(), escalation = CloudEscalation.Offline) }
        val viewModel = AskViewModel(AskQuestionUseCase(fake))

        viewModel.onQuestionChange("question")
        viewModel.ask()
        advance()

        val state = viewModel.uiState.value
        assertEquals(AskPhase.INSUFFICIENT, state.phase)
        assertTrue(state.isOffline)
        assertFalse(state.isCloudAnswer)
        assertFalse(state.canSave)
    }

    @Test
    fun cloudUnavailableIsReported() {
        val fake = FakeRag { question -> RagResponse(question, "Local memory does not contain enough evidence.", AnswerStatus.INSUFFICIENT_EVIDENCE, emptyList(), emptyList(), escalation = CloudEscalation.Unavailable) }
        val viewModel = AskViewModel(AskQuestionUseCase(fake))

        viewModel.onQuestionChange("question")
        viewModel.ask()
        advance()

        val state = viewModel.uiState.value
        assertTrue(state.isCloudUnavailable)
        assertFalse(state.canSave)
    }

    @Test
    fun saveToMemoryOnlySavesExplicitCloudAnswers() {
        val fake = FakeRag { question ->
            RagResponse(
                question = question,
                answer = "Cloud answer v42",
                status = AnswerStatus.ANSWERED,
                sources = emptyList(),
                evidence = emptyList(),
                escalation = CloudEscalation.Answered(question, "Cloud answer v42", "hub-1"),
            )
        }
        val cache = FakeCache { CacheCloudAnswerResult.AlreadyPresent }
        val viewModel = AskViewModel(AskQuestionUseCase(fake), CacheCloudAnswerUseCase(cache))

        viewModel.onQuestionChange("Some question")
        viewModel.ask()
        advance()

        assertEquals(0, cache.saves)
        viewModel.saveToMemory()
        advance()

        assertEquals("save is explicit", 1, cache.saves)
        assertEquals("Some question", cache.lastQuestion)
        assertEquals("hub-1", cache.lastAuthority)
        val state = viewModel.uiState.value
        assertTrue(state.savedToMemory)
        assertFalse(state.canSave)
    }

    @Test
    fun saveToMemoryIsIgnoredForLocalAnswers() {
        val fake = FakeRag { question -> RagResponse(question, "local", AnswerStatus.ANSWERED, emptyList(), emptyList()) }
        val cache = FakeCache { CacheCloudAnswerResult.AlreadyPresent }
        val viewModel = AskViewModel(AskQuestionUseCase(fake), CacheCloudAnswerUseCase(cache))

        viewModel.onQuestionChange("question")
        viewModel.ask()
        advance()
        viewModel.saveToMemory()
        advance()

        assertEquals("no cloud answer present, nothing may be saved", 0, cache.saves)
    }

    @Test
    fun conflictingSaveShowsMessageWithoutClaimingSaved() {
        val fake = FakeRag { question ->
            RagResponse(
                question = question,
                answer = "Cloud says 52 Nm.",
                status = AnswerStatus.ANSWERED,
                sources = emptyList(),
                evidence = emptyList(),
                escalation = CloudEscalation.Answered(question, "Cloud says 52 Nm.", "hub-1"),
            )
        }
        val cache = FakeCache { CacheCloudAnswerResult.ConflictPrevented("not saved: existing local knowledge for this question differs") }
        val viewModel = AskViewModel(AskQuestionUseCase(fake), CacheCloudAnswerUseCase(cache))

        viewModel.onQuestionChange("P-101 torque?")
        viewModel.ask()
        advance()
        viewModel.saveToMemory()
        advance()

        val state = viewModel.uiState.value
        assertFalse(state.savedToMemory)
        assertTrue(state.cacheMessage?.contains("not saved") == true)
        assertTrue(state.canSave)
    }

    private fun advance() {
        mainDispatcher.scheduler.advanceUntilIdle()
    }

    private class FakeRag(
        private val behavior: (String) -> RagResponse,
    ) : RagService {
        val sawStages = mutableListOf<RagStage>()

        override suspend fun answer(request: RagRequest, onStage: (RagStage) -> Unit): RagResponse {
            onStage(RagStage.RETRIEVING)
            onStage(RagStage.GENERATING)
            sawStages.addAll(listOf(RagStage.RETRIEVING, RagStage.GENERATING))
            return behavior(request.question)
        }
    }

    private class FakeCache(
        private val result: (String) -> CacheCloudAnswerResult,
    ) : CloudAnswerCache {
        var saves = 0
        var lastQuestion: String? = null
        var lastAnswer: String? = null
        var lastAuthority: String? = null

        override suspend fun save(question: String, answer: String, authority: String?): CacheCloudAnswerResult {
            saves++
            lastQuestion = question
            lastAnswer = answer
            lastAuthority = authority
            return result(question)
        }
    }
}