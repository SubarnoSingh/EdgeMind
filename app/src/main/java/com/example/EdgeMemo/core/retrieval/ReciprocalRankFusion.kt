package com.example.EdgeMemo.core.retrieval

/**
 * Reciprocal Rank Fusion. Combines independent ranked lists using
 * `score(d) = Σ 1 / (k + rank_i(d))`, which is scale-free and needs no score
 * calibration between dense and keyword retrieval. Fully deterministic: ties
 * are broken by id ascending.
 */
object ReciprocalRankFusion {

    const val DEFAULT_K = 60.0

    fun fuse(
        denseRanking: List<String>,
        keywordRanking: List<String>,
        k: Double = DEFAULT_K,
    ): List<Pair<String, Double>> {
        val scores = LinkedHashMap<String, Double>()
        denseRanking.forEachIndexed { index, id ->
            scores[id] = (scores[id] ?: 0.0) + 1.0 / (k + index + 1)
        }
        keywordRanking.forEachIndexed { index, id ->
            scores[id] = (scores[id] ?: 0.0) + 1.0 / (k + index + 1)
        }
        return scores.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key },
            )
            .map { it.key to it.value }
    }
}
