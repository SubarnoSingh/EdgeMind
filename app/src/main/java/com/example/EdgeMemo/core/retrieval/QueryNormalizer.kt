package com.example.EdgeMemo.core.retrieval

import com.example.EdgeMemo.core.document.TextNormalizer

/**
 * Deterministic query normalization and tokenization shared by dense and
 * keyword retrieval. No randomness and no locale-sensitive behaviour.
 */
object QueryNormalizer {

    private val tokenRegex = Regex("[a-z0-9]+(?:[-_/][a-z0-9]+)*")

    /** Collapses whitespace/control characters and lowercases for matching. */
    fun normalize(text: String): String = TextNormalizer.normalizeInline(text).lowercase()

    /** Distinct normalized tokens in appearance order, stopwords removed. */
    fun tokens(normalized: String): List<String> =
        tokenRegex.findAll(normalized)
            .map { it.value }
            .distinct()
            .filterNot { it in STOPWORDS }
            .toList()

    /**
     * Minimal English function-word list. Prevents trivial words like "the" from
     * producing keyword hits that would trip the RAG sufficiency gate.
     */
    private val STOPWORDS: Set<String> = setOf(
        "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "did",
        "do", "does", "for", "from", "has", "have", "how", "in", "is", "it",
        "its", "not", "of", "on", "or", "should", "that", "the", "this", "to",
        "was", "were", "what", "when", "where", "which", "who", "why", "will",
        "with", "would",
    )

    /**
     * A token that looks like a technical identifier (contains both a letter and
     * a digit), e.g. `skf-6205`, `e-4417`, `p-101`.
     */
    fun isIdentifier(token: String): Boolean =
        token.length >= 3 && token.any { it.isLetter() } && token.any { it.isDigit() }
}
