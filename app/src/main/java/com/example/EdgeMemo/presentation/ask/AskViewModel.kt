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

class AskViewModel(
    private val askQuestion: AskQuestionUseCase,
    private val cacheCloudAnswer: CacheCloudAnswerUseCase? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AskUiState())
    val uiState: StateFlow<AskUiState> = _uiState.asStateFlow()

    fun onQuestionChange(value: String) {
        _uiState.update { it.copy(question = value) }
    }

    fun ask() {
        val question = _uiState.value.question.trim()
        if (question.isEmpty()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    // The submitted query moves into the conversation history;
                    // the input field is cleared so it is never shown twice.
                    question = "",
                    submittedQuestion = question,
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
            val response = try {
                askQuestion(RagRequest(question = question)) { stage ->
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
                    it.copy(phase = AskPhase.ERROR, errorMessage = e.message ?: "ask failed")
                }
                return@launch
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
                        it.copy(savedToMemory = true, cacheInFlight = false, cacheMessage = "Saved to local memory.")
                    }
                    CacheCloudAnswerResult.AlreadyPresent -> _uiState.update {
                        it.copy(savedToMemory = true, cacheInFlight = false, cacheMessage = "Already in local memory.")
                    }
                    is CacheCloudAnswerResult.ConflictPrevented -> _uiState.update {
                        it.copy(savedToMemory = false, cacheInFlight = false, cacheMessage = result.reason)
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        cacheInFlight = false,
                        cacheMessage = "Save failed: ${e.message ?: "unknown error"}",
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

    fun clear() {
        _uiState.value = AskUiState()
    }
}