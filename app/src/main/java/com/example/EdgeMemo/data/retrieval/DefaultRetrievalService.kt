package com.example.EdgeMemo.data.retrieval

import com.example.EdgeMemo.ai.embedding.EmbeddingService
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.model.MemoryMetadataKeys
import com.example.EdgeMemo.core.retrieval.EvidenceItem
import com.example.EdgeMemo.core.retrieval.QueryNormalizer
import com.example.EdgeMemo.core.retrieval.ReciprocalRankFusion
import com.example.EdgeMemo.core.retrieval.RetrievalQuery
import com.example.EdgeMemo.core.retrieval.RetrievalResult
import com.example.EdgeMemo.core.retrieval.RetrievalService
import com.example.EdgeMemo.data.local.room.MemoryDao
import com.example.EdgeMemo.data.local.room.MemoryMappers.toDomain
import com.example.EdgeMemo.native.qdrant.LocalVectorStore
import com.example.EdgeMemo.native.qdrant.QdrantNativeException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hybrid retrieval pipeline:
 *
 * normalize → local embedding → Qdrant Edge dense search
 *          + local keyword/exact search
 *          → Reciprocal Rank Fusion
 *          → resolve metadata → remove tombstoned/superseded
 *          → deduplicate → rank → top-K evidence.
 *
 * Uses the existing Qdrant Edge store; no second vector database is introduced.
 */
class DefaultRetrievalService(
    private val embeddingService: EmbeddingService,
    private val vectorStore: LocalVectorStore,
    private val dao: MemoryDao,
    private val keywordRetriever: KeywordRetriever,
    private val fusionK: Double = ReciprocalRankFusion.DEFAULT_K,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RetrievalService {

    override suspend fun retrieve(query: RetrievalQuery): RetrievalResult = withContext(dispatcher) {
        val normalized = QueryNormalizer.normalize(query.text)
        if (normalized.isEmpty()) {
            throw EdgeError.InvalidInput("retrieval query is empty")
        }
        val terms = QueryNormalizer.tokens(normalized)
        val limit = query.limit.coerceAtLeast(1)
        val candidateLimit = limit * query.options.candidateMultiplier.coerceAtLeast(1)

        val dense = denseSearch(normalized, candidateLimit)
        val keyword = if (query.options.useHybrid) {
            keywordRetriever.retrieve(terms, candidateLimit)
        } else {
            emptyList()
        }

        val fused = ReciprocalRankFusion.fuse(
            denseRanking = dense.map { it.first },
            keywordRanking = keyword.map { it.memoryId },
            k = fusionK,
        )
        val denseScores = dense.toMap()
        val keywordScores = keyword.associate { it.memoryId to it.score }
        val keywordTerms = keyword.associate { it.memoryId to it.matchedTerms }

        val ids = fused.map { it.first }
        val entities = if (ids.isEmpty()) emptyMap() else dao.getByIds(ids).associateBy { it.memoryId }
        val superseded = dao.supersededIds().toHashSet()

        val items = ArrayList<EvidenceItem>(fused.size)
        for ((id, score) in fused) {
            val entity = entities[id] ?: continue
            if (entity.tombstone) continue
            if (id in superseded) continue
            items.add(
                EvidenceItem(
                    memory = entity.toDomain(),
                    score = score,
                    denseScore = denseScores[id],
                    keywordScore = keywordScores[id],
                    rank = 0,
                    matchedTerms = keywordTerms[id].orEmpty(),
                ),
            )
        }

        val deduplicated = if (query.options.deduplicate) deduplicate(items) else items
        val ranked = deduplicated
            .take(limit)
            .mapIndexed { index, item -> item.copy(rank = index + 1) }

        RetrievalResult(
            query = query.text,
            normalizedQuery = normalized,
            evidence = ranked,
            candidateCount = fused.size,
        )
    }

    private suspend fun denseSearch(normalized: String, limit: Int): List<Pair<String, Double>> {
        val vector = try {
            embeddingService.embed(normalized)
        } catch (e: EdgeError) {
            throw EdgeError.EmbeddingError(e.message ?: "query embedding failed", e)
        } catch (e: Exception) {
            throw EdgeError.EmbeddingError("query embedding failed: ${e.message}", e)
        }

        vectorStore.ensureReady(embeddingService.dimension)
        return try {
            vectorStore.search(vector, limit).map { it.id to it.score }
        } catch (e: QdrantNativeException) {
            throw EdgeError.QdrantError("vector search failed: ${e.message}", e)
        } catch (e: Exception) {
            throw EdgeError.QdrantError("vector search failed: ${e.message}", e)
        }
    }

    private fun deduplicate(items: List<EvidenceItem>): List<EvidenceItem> {
        val seenHashes = HashSet<String>()
        val seenChunks = HashSet<String>()
        val result = ArrayList<EvidenceItem>(items.size)
        for (item in items) {
            if (!seenHashes.add(item.memory.contentHash)) continue
            if (!seenChunks.add(chunkKey(item))) continue
            result.add(item)
        }
        return result
    }

    private fun chunkKey(item: EvidenceItem): String {
        val memory = item.memory
        val documentId = memory.metadata[MemoryMetadataKeys.DOCUMENT_ID]
        val chunkIndex = memory.metadata[MemoryMetadataKeys.CHUNK_INDEX]
        return if (documentId != null && chunkIndex != null) {
            "$documentId#$chunkIndex"
        } else {
            memory.chunkId ?: memory.memoryId
        }
    }
}
