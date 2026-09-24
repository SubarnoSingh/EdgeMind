package com.example.EdgeMemo.domain.rag

import com.example.EdgeMemo.ai.llm.LLMService
import com.example.EdgeMemo.ai.llm.LlmEvidence
import com.example.EdgeMemo.ai.llm.LlmRequest
import com.example.EdgeMemo.core.model.MemoryMetadataKeys
import com.example.EdgeMemo.core.rag.AnswerStatus
import com.example.EdgeMemo.core.rag.RagError
import com.example.EdgeMemo.core.rag.RagRequest
import com.example.EdgeMemo.core.rag.RagResponse
import com.example.EdgeMemo.core.rag.RagStage
import com.example.EdgeMemo.core.rag.SourceReference
import com.example.EdgeMemo.core.retrieval.EvidenceItem
import com.example.EdgeMemo.core.retrieval.RetrievalQuery
import com.example.EdgeMemo.core.retrieval.RetrievalService

/**
 * Grounded RAG over local evidence only.
 *
 * The service retrieves evidence, decides whether it is sufficient, and asks the
 * (replaceable) [LLMService] for a controlled answer. Answers are always kept
 * separate from evidence, every citation maps to a real retrieved memory, and
 * insufficient evidence is stated explicitly rather than filled from model
 * knowledge.
 */
class DefaultRagService(
    private val retrievalService: RetrievalService,
    private val llmService: LLMService,
    private val minDenseScore: Double = DEFAULT_MIN_DENSE_SCORE,
) : RagService {

    override suspend fun answer(request: RagRequest, onStage: (RagStage) -> Unit): RagResponse {
        val question = request.question.trim()
        if (question.isEmpty()) {
            return RagResponse(
                question = question,
                answer = INSUFFICIENT_MESSAGE,
                status = AnswerStatus.ERROR,
                sources = emptyList(),
                evidence = emptyList(),
                error = RagError.EmptyQuestion(),
            )
        }

        onStage(RagStage.RETRIEVING)
        val result = try {
            retrievalService.retrieve(
                RetrievalQuery(text = question, limit = request.limit, options = request.options),
            )
        } catch (e: Exception) {
            return RagResponse(
                question = question,
                answer = RETRIEVAL_FAILED_MESSAGE,
                status = AnswerStatus.ERROR,
                sources = emptyList(),
                evidence = emptyList(),
                error = RagError.RetrievalFailed(e.message ?: "retrieval failed", e),
            )
        }

        val evidence = result.evidence
        if (!isSufficient(evidence)) {
            return RagResponse(
                question = question,
                answer = INSUFFICIENT_MESSAGE,
                status = AnswerStatus.INSUFFICIENT_EVIDENCE,
                sources = buildSources(evidence),
                evidence = evidence,
            )
        }

        onStage(RagStage.GENERATING)
        val completion = try {
            llmService.answer(
                LlmRequest(
                    question = question,
                    evidence = evidence.map { it.toLlmEvidence() },
                ),
            )
        } catch (e: Exception) {
            return RagResponse(
                question = question,
                answer = GENERATION_FAILED_MESSAGE,
                status = AnswerStatus.ERROR,
                sources = buildSources(evidence),
                evidence = evidence,
                error = RagError.GenerationFailed(e.message ?: "answer generation failed", e),
            )
        }

        val used = completion.usedEvidenceIndexes.toSet()
        val cited = evidence.filter { it.rank in used }
        val sources = buildSources(if (cited.isNotEmpty()) cited else evidence)

        return RagResponse(
            question = question,
            answer = completion.answer,
            status = AnswerStatus.ANSWERED,
            sources = sources,
            evidence = evidence,
        )
    }

    private fun isSufficient(evidence: List<EvidenceItem>): Boolean {
        if (evidence.isEmpty()) return false
        val hasKeywordMatch = evidence.any { (it.keywordScore ?: 0.0) > 0.0 }
        val topDense = evidence.maxOfOrNull { it.denseScore ?: 0.0 } ?: 0.0
        return hasKeywordMatch || topDense >= minDenseScore
    }

    private fun buildSources(evidence: List<EvidenceItem>): List<SourceReference> =
        evidence.map { item ->
            val memory = item.memory
            SourceReference(
                index = item.rank,
                memoryId = memory.memoryId,
                chunkId = memory.chunkId,
                title = memory.title,
                source = memory.source,
                type = memory.type,
                page = memory.metadata[MemoryMetadataKeys.PAGE]?.toIntOrNull(),
                section = memory.metadata[MemoryMetadataKeys.SECTION],
                chunkIndex = memory.metadata[MemoryMetadataKeys.CHUNK_INDEX]?.toIntOrNull(),
                score = item.score,
                snippet = snippet(memory.content),
            )
        }

    private fun snippet(content: String): String =
        if (content.length <= SNIPPET_LENGTH) content else content.take(SNIPPET_LENGTH).trimEnd() + "\u2026"

    private fun EvidenceItem.toLlmEvidence(): LlmEvidence = LlmEvidence(
        index = rank,
        title = memory.title,
        source = memory.source,
        text = memory.content,
        page = memory.metadata[MemoryMetadataKeys.PAGE]?.toIntOrNull(),
        section = memory.metadata[MemoryMetadataKeys.SECTION],
    )

    companion object {
        const val DEFAULT_MIN_DENSE_SCORE = 0.25

        const val INSUFFICIENT_MESSAGE =
            "Local memory does not contain enough evidence to answer this question."

        const val RETRIEVAL_FAILED_MESSAGE =
            "Local retrieval failed, so no grounded answer could be produced."

        const val GENERATION_FAILED_MESSAGE =
            "Evidence was found, but a grounded answer could not be generated."

        private const val SNIPPET_LENGTH = 240
    }
}
