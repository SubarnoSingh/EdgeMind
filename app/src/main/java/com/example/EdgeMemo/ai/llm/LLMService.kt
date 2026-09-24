package com.example.EdgeMemo.ai.llm

data class LlmEvidence(
    /** 1-based index used for `[n]` citations in the answer. */
    val index: Int,
    val title: String,
    val source: String,
    val text: String,
    val page: Int? = null,
    val section: String? = null,
)

data class LlmRequest(
    val question: String,
    val evidence: List<LlmEvidence>,
    val maxSentences: Int = 4,
)

data class LlmResponse(
    val answer: String,
    val usedEvidenceIndexes: List<Int>,
)

/**
 * Replaceable answer engine. Implementations must answer only from the supplied
 * evidence and must never invent facts or citations. A local LLM can be added
 * behind this interface later without changing the RAG pipeline.
 */
interface LLMService {
    val name: String

    suspend fun answer(request: LlmRequest): LlmResponse
}
