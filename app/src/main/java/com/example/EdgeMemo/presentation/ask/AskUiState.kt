package com.example.EdgeMemo.presentation.ask

import com.example.EdgeMemo.core.rag.CloudEscalation
import com.example.EdgeMemo.core.rag.SourceReference
import com.example.EdgeMemo.core.retrieval.EvidenceItem

enum class AskPhase {
    IDLE,
    RETRIEVING,
    GENERATING,
    /** Local evidence was insufficient and a cloud escalation is being attempted. */
    ESCALATING,
    SUCCESS,
    INSUFFICIENT,
    ERROR,
}

data class AskUiState(
    /** Live text of the bottom input field. Cleared on submit. */
    val question: String = "",
    /** The last submitted query, rendered as the conversation's user message. */
    val submittedQuestion: String? = null,
    val phase: AskPhase = AskPhase.IDLE,
    val answer: String = "",
    val sources: List<SourceReference> = emptyList(),
    val evidence: List<EvidenceItem> = emptyList(),
    val errorMessage: String? = null,
    val expandedSourceIndex: Int? = null,
    /** Cloud escalation outcome of the last ask (null = purely local response). */
    val escalation: CloudEscalation? = null,
    /** True while the explicit "save to memory" action is running. */
    val cacheInFlight: Boolean = false,
    /** True once the cloud answer has been saved to local memory by the user. */
    val savedToMemory: Boolean = false,
    /** Human message about the save attempt (saved / already present / refused). */
    val cacheMessage: String? = null,
) {
    val isBusy: Boolean
        get() = phase == AskPhase.RETRIEVING || phase == AskPhase.GENERATING || phase == AskPhase.ESCALATING

    val hasResult: Boolean
        get() = phase == AskPhase.SUCCESS || phase == AskPhase.INSUFFICIENT

    /** True once any conversation content exists (a question was submitted). */
    val hasConversation: Boolean
        get() = submittedQuestion != null || isBusy || hasResult || errorMessage != null

    val isCloudAnswer: Boolean
        get() = escalation is CloudEscalation.Answered

    val isOffline: Boolean
        get() = escalation == CloudEscalation.Offline

    val isCloudUnavailable: Boolean
        get() = escalation == CloudEscalation.Unavailable

    val canSave: Boolean
        get() = isCloudAnswer && !savedToMemory && !cacheInFlight
}