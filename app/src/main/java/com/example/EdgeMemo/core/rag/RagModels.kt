package com.example.EdgeMemo.core.rag

import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.retrieval.EvidenceItem
import com.example.EdgeMemo.core.retrieval.RetrievalOptions

enum class AnswerStatus {
    ANSWERED,
    INSUFFICIENT_EVIDENCE,
    ERROR,
}

/** Real, observable stages of a RAG request (no fabricated percentages). */
enum class RagStage {
    RETRIEVING,
    GENERATING,
    /** Optional edge → cloud escalation being attempted (only after local insufficiency). */
    ESCALATING,
}

data class RagRequest(
    val question: String,
    val limit: Int = 5,
    val options: RetrievalOptions = RetrievalOptions(),
)

/**
 * A citation pointing at a real stored memory. [index] matches the `[n]` marker
 * used in the answer text; [score] is the fused retrieval score.
 */
data class SourceReference(
    val index: Int,
    val memoryId: String,
    val chunkId: String?,
    val title: String,
    val source: String,
    val type: MemoryType,
    val page: Int?,
    val section: String?,
    val chunkIndex: Int?,
    val score: Double,
    val snippet: String,
)

/**
 * Optional cloud escalation outcome carried on a [RagResponse].
 *
 * - `null` — answered from local evidence (or a local/answer-generation error).
 * - [Answered] — local evidence was insufficient, the device was online, and a
 *   cloud endpoint returned an answer. The answer is *attributed with its
 *   provenance* and is NOT local evidence: it must never be auto-stored. Only an
 *   explicit user action may localize it.
 * - [Offline] — local evidence was insufficient and the device is offline.
 * - [Unavailable] — local evidence was insufficient, the device was online, but
 *   the cloud could not answer (unreachable/error). Answered honestly as a
 *   limitation, never faked.
 */
sealed interface CloudEscalation {
    data class Answered(
        val question: String,
        val answer: String,
        val authority: String?,
    ) : CloudEscalation

    data object Offline : CloudEscalation

    data object Unavailable : CloudEscalation
}

data class RagResponse(
    val question: String,
    val answer: String,
    val status: AnswerStatus,
    val sources: List<SourceReference>,
    val evidence: List<EvidenceItem>,
    val error: RagError? = null,
    /** Cloud escalation outcome, if any. Null means a purely local response. */
    val escalation: CloudEscalation? = null,
)

sealed class RagError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class EmptyQuestion : RagError("question is empty")

    class RetrievalFailed(message: String, cause: Throwable? = null) : RagError(message, cause)

    class GenerationFailed(message: String, cause: Throwable? = null) : RagError(message, cause)
}
