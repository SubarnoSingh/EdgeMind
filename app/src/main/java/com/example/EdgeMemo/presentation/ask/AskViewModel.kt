package com.example.EdgeMemo.presentation.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.EdgeMemo.core.rag.AnswerStatus
import com.example.EdgeMemo.core.rag.CloudEscalation
import com.example.EdgeMemo.core.rag.RagRequest
import com.example.EdgeMemo.core.rag.RagStage
import com.example.EdgeMemo.domain.cloud.CacheCloudAnswerResult
import com.example.EdgeMemo.domain.cloud.CacheCloudAnswerUseCase
import com.example.EdgeMemo.domain.rag.AskQuestionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Production Ask/RAG ViewModel. Coordinates the EXISTING application/domain
 * pipeline only ([AskQuestionUseCase] → escalating → Qdrant-grounded RAG);
 * it performs no retrieval, filtering, or provenance logic of its own.
 *
 * States: IDLE → RETRIEVING → (GENERATING | ESCALATING) →
 * SUCCESS | INSUFFICIENT | ERROR. Insufficient evidence is a first-class,
 * non-error outcome; cloud escalation is reported only when the real domain
 * state carries it.
 */
class AskViewModel(
    private val askQuestion: AskQuestionUseCase,
    private val cacheCloudAnswer: CacheCloudAnswerUseCase? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AskUiState())
    val uiState: StateFlow<AskUiState> = _uiState.asStateFlow()

    /** Text of the last executed request, so retry replays exactly it. */
    private var lastExecutedQuery: String? = null

    fun onQuestionChange(value: String) {
        _uiState.update { it.copy(question = value) }
    }

    /**
     * Opens Ask with an asset context (from Machine Detail). The namespace is
     * surfaced in the UI and appended to the executed query by [ask] — real
     * query text through the real pipeline, never a UI-side result filter.
     */
    fun setAssetContext(namespace: String?) {
        _uiState.update { it.copy(assetNamespace = namespace?.trim()?.lowercase()?.ifBlank { null }) }
    }

    fun ask() {
        val question = _uiState.value.question.trim()
        if (question.isEmpty()) return
        submit(question)
    }

    /** Re-executes the last executed query verbatim (error retry). */
    fun retry() {
        val previous = lastExecutedQuery ?: _uiState.value.executedQuestion ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(errorMessage = null, phase = AskPhase.RETRIEVING)
            }
            execute(previous)
        }
    }

    private fun submit(rawQuestion: String) {
        val asset = _uiState.value.assetNamespace
        // Honest asset grounding: the real token joins the query text so the
        // pipeline's own identifier-weighted keyword channel and dense
        // similarity act on it. No result set is filtered here.
        val executed = if (asset != null && !normalizeForContains(rawQuestion).contains(asset)) {
            "$rawQuestion $asset"
        } else {
            rawQuestion
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    // The submitted query moves into the conversation history;
                    // the input field is cleared so it is never shown twice.
                    question = "",
                    submittedQuestion = rawQuestion,
                    executedQuestion = executed,
                    phase = AskPhase.RETRIEVING,
                    answer = "",
                    sources = emptyList(),
                    evidence = emptyList(),
                    errorMessage = null,
                    expandedSourceIndex = null,
                    escalation = null,
                    cacheInFlight = false,
                    savedToMemory = false,
                    cacheMessage = null,
                )
            }
            execute(executed)
        }
    }

    private suspend fun execute(executedQuestion: String) {
        lastExecutedQuery = executedQuestion
        val response = try {
            askQuestion(RagRequest(question = executedQuestion)) { stage ->
                _uiState.update {
                    it.copy(
                        phase = when (stage) {
                            RagStage.RETRIEVING -> AskPhase.RETRIEVING
                            RagStage.GENERATING -> AskPhase.GENERATING
                            RagStage.ESCALATING -> AskPhase.ESCALATING
                        },
                    )
                }
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(phase = AskPhase.ERROR, errorMessage = e.message ?: "The question couldn't be answered.")
            }
            return
        }

        when (response.status) {
            AnswerStatus.ANSWERED -> _uiState.update {
                it.copy(
                    phase = AskPhase.SUCCESS,
                    answer = response.answer,
                    sources = response.sources,
                    evidence = response.evidence,
                    escalation = response.escalation,
                )
            }
            AnswerStatus.INSUFFICIENT_EVIDENCE -> _uiState.update {
                it.copy(
                    phase = AskPhase.INSUFFICIENT,
                    answer = response.answer,
                    sources = response.sources,
                    evidence = response.evidence,
                    escalation = response.escalation,
                )
            }
            AnswerStatus.ERROR -> _uiState.update {
                it.copy(
                    phase = AskPhase.ERROR,
                    answer = response.answer,
                    sources = response.sources,
                    evidence = response.evidence,
                    errorMessage = response.error?.message ?: response.answer,
                    escalation = response.escalation,
                )
            }
        }
    }

    /**
     * Explicit user action. A cloud answer is NEVER stored automatically; only
     * this call may localize it, and it does so through the approved cache path.
     */
    fun saveToMemory() {
        val escalation = _uiState.value.escalation as? CloudEscalation.Answered ?: return
        val useCase = cacheCloudAnswer ?: return
        if (!_uiState.value.canSave) return
        viewModelScope.launch {
            _uiState.update { it.copy(cacheInFlight = true, cacheMessage = null) }
            try {
                when (val result = useCase(escalation.question, escalation.answer, escalation.authority)) {
                    is CacheCloudAnswerResult.Saved -> _uiState.update {
                        it.copy(savedToMemory = true, cacheInFlight = false, cacheMessage = "Saved to this device.")
                    }
                    CacheCloudAnswerResult.AlreadyPresent -> _uiState.update {
                        it.copy(savedToMemory = true, cacheInFlight = false, cacheMessage = "Already saved on this device.")
                    }
                    is CacheCloudAnswerResult.ConflictPrevented -> _uiState.update {
                        it.copy(savedToMemory = false, cacheInFlight = false, cacheMessage = result.reason)
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        cacheInFlight = false,
                        cacheMessage = "Couldn't save: ${e.message ?: "unknown error"}",
                    )
                }
            }
        }
    }

    fun toggleSource(index: Int) {
        _uiState.update {
            it.copy(expandedSourceIndex = if (it.expandedSourceIndex == index) null else index)
        }
    }

    /** Full reset — a new question starts without asset context. */
    fun clear() {
        lastExecutedQuery = null
        _uiState.value = AskUiState()
    }

    private fun normalizeForContains(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }
}
