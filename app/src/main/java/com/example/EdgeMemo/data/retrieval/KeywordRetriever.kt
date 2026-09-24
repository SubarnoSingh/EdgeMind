package com.example.EdgeMemo.data.retrieval

import com.example.EdgeMemo.core.retrieval.QueryNormalizer
import com.example.EdgeMemo.data.local.room.MemoryDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class KeywordHit(
    val memoryId: String,
    val score: Double,
    val matchedTerms: List<String>,
)

/**
 * Lightweight local keyword/exact retrieval over Room. Each query term is
 * matched case-insensitively against title/content; technical identifiers
 * (`p-101`, `skf-6205`) are weighted higher so exact matches survive fusion.
 * No external search engine or second database is used.
 */
class KeywordRetriever(
    private val dao: MemoryDao,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun retrieve(terms: List<String>, limit: Int): List<KeywordHit> =
        withContext(dispatcher) {
            if (terms.isEmpty() || limit <= 0) return@withContext emptyList()

            val scores = HashMap<String, Double>()
            val matched = HashMap<String, LinkedHashSet<String>>()

            for (term in terms) {
                val weight = if (QueryNormalizer.isIdentifier(term)) IDENTIFIER_WEIGHT else TERM_WEIGHT
                for (row in dao.searchByKeyword(term, limit)) {
                    scores[row.memoryId] = (scores[row.memoryId] ?: 0.0) + weight
                    matched.getOrPut(row.memoryId) { LinkedHashSet() }.add(term)
                }
            }

            scores.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key },
                )
                .take(limit)
                .map { KeywordHit(it.key, it.value, matched[it.key]?.toList().orEmpty()) }
        }

    private companion object {
        const val TERM_WEIGHT = 1.0
        const val IDENTIFIER_WEIGHT = 2.0
    }
}
