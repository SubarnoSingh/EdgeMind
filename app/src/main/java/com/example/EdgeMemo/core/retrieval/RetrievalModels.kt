package com.example.EdgeMemo.core.retrieval

import com.example.EdgeMemo.core.model.Memory

data class RetrievalOptions(
    /** Enable keyword/exact retrieval in addition to dense vector search. */
    val useHybrid: Boolean = true,
    /** Candidate pool = limit * candidateMultiplier before fusion/filtering. */
    val candidateMultiplier: Int = 4,
    /** Collapse exact duplicate and same-chunk evidence. */
    val deduplicate: Boolean = true,
)

data class RetrievalQuery(
    val text: String,
    val limit: Int = 5,
    val options: RetrievalOptions = RetrievalOptions(),
)

/**
 * A single piece of evidence, retaining enough information to explain why it
 * was returned: fused [score], the contributing [denseScore]/[keywordScore],
 * [matchedTerms], and its final [rank] (1-based).
 */
data class EvidenceItem(
    val memory: Memory,
    val score: Double,
    val denseScore: Double?,
    val keywordScore: Double?,
    val rank: Int,
    val matchedTerms: List<String> = emptyList(),
)

data class RetrievalResult(
    val query: String,
    val normalizedQuery: String,
    val evidence: List<EvidenceItem>,
    /** Number of fused candidates before filtering/deduplication/top-K. */
    val candidateCount: Int,
)
