package com.example.EdgeMemo.ai.llm

import com.example.EdgeMemo.core.retrieval.QueryNormalizer

/**
 * Controlled, extractive answer engine used for the offline core path.
 *
 * The answer is composed *only* from verbatim sentences found in the retrieved
 * evidence. Sentences that overlap the question terms are preferred, each
 * annotated with a `[n]` citation to the evidence it came from. When no sentence
 * overlaps the question, the top evidence's first sentence is quoted verbatim.
 *
 * It never paraphrases from model knowledge, never fabricates citations, and
 * never adds facts that are not in the evidence.
 */
class ExtractiveLLMService : LLMService {

    override val name: String = "extractive-local"

    override suspend fun answer(request: LlmRequest): LlmResponse {
        if (request.evidence.isEmpty()) {
            return LlmResponse(answer = "", usedEvidenceIndexes = emptyList())
        }

        val queryTerms = QueryNormalizer
            .tokens(QueryNormalizer.normalize(request.question))
            .toSet()

        val selected = ArrayList<Pair<Int, String>>()
        val seenSentences = HashSet<String>()

        for (evidence in request.evidence) {
            if (selected.size >= request.maxSentences) break
            for (sentence in splitSentences(evidence.text)) {
                if (selected.size >= request.maxSentences) break
                val normalizedSentence = QueryNormalizer.normalize(sentence)
                if (normalizedSentence.isEmpty()) continue
                val overlap = QueryNormalizer.tokens(normalizedSentence).count { it in queryTerms }
                if (overlap <= 0) continue
                if (!seenSentences.add(normalizedSentence)) continue
                selected.add(evidence.index to sentence.trim())
            }
        }

        if (selected.isEmpty()) {
            val top = request.evidence.first()
            val snippet = splitSentences(top.text).firstOrNull()?.trim().orEmpty()
            if (snippet.isNotEmpty()) {
                seenSentences.add(QueryNormalizer.normalize(snippet))
                selected.add(top.index to snippet)
            }
        }

        val answer = buildString {
            selected.forEachIndexed { position, (index, sentence) ->
                if (position > 0) append(' ')
                append(sentence)
                append(" [").append(index).append(']')
            }
        }
        val used = selected.map { it.first }.distinct()
        return LlmResponse(answer = answer, usedEvidenceIndexes = used)
    }

    private fun splitSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return text.split(SENTENCE_BOUNDARY)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private companion object {
        val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+")
    }
}
