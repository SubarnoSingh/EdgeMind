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

        // Score every sentence, keep the best ones (at most MAX_PER_SOURCE per
        // evidence so answers span records), then emit in evidence order.
        data class Candidate(val rank: Int, val position: Int, val index: Int, val sentence: String, val score: Int)
        val seenSentences = HashSet<String>()
        val candidates = ArrayList<Candidate>()
        request.evidence.forEachIndexed { rank, evidence ->
            splitSentences(evidence.text).forEachIndexed { position, sentence ->
                val normalizedSentence = QueryNormalizer.normalize(sentence)
                if (normalizedSentence.isEmpty() || !seenSentences.add(normalizedSentence)) return@forEachIndexed
                val score = QueryNormalizer.tokens(normalizedSentence).sumOf { token ->
                    queryTerms.maxOfOrNull { matchWeight(it, token) } ?: 0
                }
                if (score > 0) candidates.add(Candidate(rank, position, evidence.index, sentence.trim(), score))
            }
        }
        val perSource = HashMap<Int, Int>()
        val selected = candidates
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.rank }.thenBy { it.position })
            .filter { perSource.merge(it.rank, 1, Int::plus)!! <= MAX_PER_SOURCE }
            .take(request.maxSentences)
            .sortedWith(compareBy<Candidate> { it.rank }.thenBy { it.position })
            .map { it.index to it.sentence }
            .toMutableList()

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

    /**
     * Exact match, a shared word core ("confirmed"/"unconfirmed",
     * "failure"/"failures"), or an identifier whose letter prefix abbreviates
     * the query word ("incident" <-> "inc-1042"). The identifier case weighs
     * most: it is the specific answer to "which incident/part/..." questions.
     */
    private fun matchWeight(term: String, token: String): Int {
        if (term == token) return 1
        val shorter = if (term.length <= token.length) term else token
        val longer = if (shorter === term) token else term
        if (shorter.length >= 5 && longer.contains(shorter)) return 1
        if (QueryNormalizer.isIdentifier(token)) {
            val prefix = token.takeWhile { it.isLetter() }
            if (prefix.length >= 3 && term.startsWith(prefix)) return IDENTIFIER_WEIGHT
        }
        return 0
    }

    private companion object {
        val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+")
        const val MAX_PER_SOURCE = 2
        const val IDENTIFIER_WEIGHT = 3
    }
}
