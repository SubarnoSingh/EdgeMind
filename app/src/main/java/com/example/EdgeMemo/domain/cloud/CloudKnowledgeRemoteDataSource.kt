package com.example.EdgeMemo.domain.cloud

/**
 * A single piece of curated cloud knowledge as delivered by an incremental
 * pull. Carries the full evolving-memory identity so the edge can compare it
 * deterministically against local state without trusting the remote blindly.
 */
data class CloudKnowledgeItem(
    val memoryId: String,
    val subjectKey: String?,
    val title: String,
    val content: String,
    val contentHash: String,
    val version: Int,
    val updatedAt: Long,
    val origin: String = "CLOUD",
    val authority: String? = null,
    val supersedes: String? = null,
    val tombstone: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
)

/** One page of an incremental pull. `nextCursor` null means "no further pages". */
data class CloudKnowledgeBatch(
    val items: List<CloudKnowledgeItem>,
    val nextCursor: String? = null,
)

/**
 * Cloud → Edge pull. The real Qdrant Server/backend is a later phase; the
 * shipped implementation honestly reports the remote as unavailable. Test
 * fixtures implement this interface against deterministic cloud items — they
 * are fixtures, never a substitute for a real endpoint.
 */
interface CloudKnowledgeRemoteDataSource {
    suspend fun pullKnowledge(cursor: String?): CloudKnowledgeBatch
}