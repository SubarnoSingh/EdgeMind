package com.example.EdgeMemo.data.retrieval

import com.example.EdgeMemo.ai.embedding.EmbeddingService
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.model.MemoryMetadataKeys
import com.example.EdgeMemo.core.record.JsonString
import com.example.EdgeMemo.core.record.LocalRecordStore
import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordFilter
import com.example.EdgeMemo.core.record.RecordQuery
import com.example.EdgeMemo.core.retrieval.EvidenceItem
import com.example.EdgeMemo.core.retrieval.QueryNormalizer
import com.example.EdgeMemo.core.retrieval.ReciprocalRankFusion
import com.example.EdgeMemo.core.retrieval.RetrievalQuery
import com.example.EdgeMemo.core.retrieval.RetrievalResult
import com.example.EdgeMemo.core.retrieval.RetrievalService
import com.example.EdgeMemo.data.repository.MemoryRecordMapper
import com.example.EdgeMemo.native.qdrant.QdrantNativeException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 13.3 — the ACTIVE retrieval pipeline over the single Qdrant Edge
 * application shard. It replaces the legacy Room + `local_qdrant`
 * [DefaultRetrievalService] with the SAME pipeline executed on Qdrant-native
 * primitives, preserving semantics exactly:
 *
 * normalize → local embedding → dense vector search (same shard, active
 * application record types only) + keyword/exact scan with identifier
 * weighting (term 1.0, identifier 2.0) → Reciprocal Rank Fusion (same K)
 * → tombstone/superseded exclusion → deduplication (content hash + chunk
 * identity) → top-K evidence with denseScore/keywordScore/matchedTerms/rank.
 *
 * The RAG layer above it (`DefaultRagService`, sufficiency incl.
 * minDenseScore 0.25, citations, escalation) is unchanged: every EvidenceItem
 * is mapped from the full Record envelope + payload, so titles, content,
 * chunk/document identity, page/section metadata and provenance survive into
 * citations without any Room join.
 *
 * Truthful implementation notes (docs/PHASE_13_3_QDRANT_RETRIEVAL_RAG_CUTOVER.md):
 *  - qdrant-edge 0.8.0 exposes NO full-text/substring search through this
 *    boundary. The keyword path is therefore a BOUNDED scan of active
 *    application records (indexed tombstone+type prefilter) evaluating the
 *    SAME case-insensitive substring rules the Room LIKE path used. Ranking,
 *    weights, and matched-term reporting are identical; only the execution
 *    site moved from SQLite to the authoritative shard.
 *  - Dense search results carry the complete record (payload travels in the
 *    same Qdrant point), so there is no second store to resolve against —
 *    structurally impossible to query two shards.
 *  - Superseded exclusion is the exhaustive indexed `_supersedes` scan,
 *    matching the legacy `SELECT DISTINCT supersedes` semantics including
 *    references from tombstoned records.
 */
class QdrantRecordRetrievalService(
    private val embeddingService: EmbeddingService,
    private val recordStore: LocalRecordStore,
    private val fusionK: Double = ReciprocalRankFusion.DEFAULT_K,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RetrievalService {

    override suspend fun retrieve(query: RetrievalQuery): RetrievalResult = withContext(dispatcher) {
        ensureStore()
        val normalized = QueryNormalizer.normalize(query.text)
        if (normalized.isEmpty()) {
            throw EdgeError.InvalidInput("retrieval query is empty")
        }
        val terms = QueryNormalizer.tokens(normalized)
        val limit = query.limit.coerceAtLeast(1)
        val candidateLimit = limit * query.options.candidateMultiplier.coerceAtLeast(1)

        val denseHits = denseSearch(normalized, candidateLimit)
        val denseRecords = denseHits.associate { it.record.id.uuid to it.record }

        val keywordHits = if (query.options.useHybrid) {
            keywordRetrieve(terms, candidateLimit)
        } else {
            emptyList()
        }

        val fused = ReciprocalRankFusion.fuse(
            denseRanking = denseHits.map { it.record.id.uuid },
            keywordRanking = keywordHits.map { it.memoryId },
            k = fusionK,
        )
        val denseScores = denseHits.associate { it.record.id.uuid to it.score }
        val keywordScores = keywordHits.associate { it.memoryId to it.score }
        val keywordTerms = keywordHits.associate { it.memoryId to it.matchedTerms }

        // Evidence payloads come from the SAME Qdrant points that were ranked —
        // dense hits carry their parsed payload; keyword-only ids resolve from
        // the scan already performed. Nothing is fetched from another store.
        val superseded = supersededIds()

        val items = ArrayList<EvidenceItem>(fused.size)
        for ((id, score) in fused) {
            val record = denseRecords[id] ?: keywordHits.firstOrNull { it.memoryId == id }?.record
                ?: continue // vanished between ranking and resolution
            if (record.tombstone) continue
            if (id in superseded) continue
            items.add(
                EvidenceItem(
                    memory = MemoryRecordMapper.toMemory(record),
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

    // ------------------------------------------------------------------
    // Dense — real vector search inside the application shard
    // ------------------------------------------------------------------

    private suspend fun denseSearch(normalized: String, limit: Int): List<ScoredHit> {
        val vector = try {
            embeddingService.embed(normalized)
        } catch (e: EdgeError) {
            throw EdgeError.EmbeddingError(e.message ?: "query embedding failed", e)
        } catch (e: Exception) {
            throw EdgeError.EmbeddingError("query embedding failed: ${e.message}", e)
        }
        return try {
            recordStore.search(
                RecordQuery.searchInTypes(
                    vector = vector,
                    recordTypes = MemoryRecordMapper.APP_MEMORY_RECORD_TYPES,
                    limit = limit,
                ),
            ).map { ScoredHit(it.record, it.score) }
        } catch (e: QdrantNativeException) {
            throw EdgeError.QdrantError("vector search failed: ${e.message}", e)
        } catch (e: Exception) {
            throw EdgeError.QdrantError("vector search failed: ${e.message}", e)
        }
    }

    // ------------------------------------------------------------------
    // Keyword/exact — same scoring as the legacy Room LIKE retriever
    // ------------------------------------------------------------------

    private data class KeywordHit(
        val memoryId: String,
        val score: Double,
        val matchedTerms: List<String>,
        val record: Record,
    )

    private data class ScoredHit(
        val record: Record,
        val score: Double,
    )

    private suspend fun keywordRetrieve(terms: List<String>, limit: Int): List<KeywordHit> {
        if (terms.isEmpty() || limit <= 0) return emptyList()

        val active = scanActiveApplicationRecords()
        val scores = HashMap<String, Double>()
        val matched = HashMap<String, LinkedHashSet<String>>()

        for (term in terms) {
            val weight = if (QueryNormalizer.isIdentifier(term)) IDENTIFIER_WEIGHT else TERM_WEIGHT
            val needle = term.lowercase()
            for (record in active) {
                val title = (record.payload[MemoryRecordMapper.FIELD_TITLE] as? JsonString)?.value.orEmpty()
                val content = (record.payload[MemoryRecordMapper.FIELD_CONTENT] as? JsonString)?.value.orEmpty()
                if (title.lowercase().contains(needle) || content.lowercase().contains(needle)) {
                    val id = record.id.uuid
                    scores[id] = (scores[id] ?: 0.0) + weight
                    matched.getOrPut(id) { LinkedHashSet() }.add(term)
                }
            }
        }

        val byId = active.associateBy { it.id.uuid }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(limit)
            .mapNotNull { (id, score) ->
                val record = byId[id] ?: return@mapNotNull null
                KeywordHit(id, score, matched[id]?.toList().orEmpty(), record)
            }
    }

    private suspend fun scanActiveApplicationRecords(): List<Record> {
        val out = ArrayList<Record>()
        var offset: String? = null
        while (out.size < SCAN_CAP) {
            val page = recordStore.scroll(
                RecordQuery.allActive(
                    recordTypes = MemoryRecordMapper.APP_MEMORY_RECORD_TYPES,
                    limit = SCAN_PAGE,
                    offsetId = offset,
                ),
            )
            out.addAll(page.records)
            if (page.nextOffsetId == null || page.records.isEmpty()) break
            offset = page.nextOffsetId
        }
        return out
    }

    // ------------------------------------------------------------------
    // Superseded exclusion — exhaustive indexed `_supersedes` scan
    // ------------------------------------------------------------------

    private suspend fun supersededIds(): Set<String> {
        val out = HashSet<String>()
        var offset: String? = null
        while (true) {
            val page = recordStore.scroll(
                RecordQuery.Scroll(
                    filter = RecordFilter.Exists("_supersedes"),
                    limit = SCAN_PAGE,
                    offsetId = offset,
                    recordTypes = null, // legacy DISTINCT covered every row
                ),
            )
            page.records.forEach { record -> record.supersedes?.let { out.add(it) } }
            if (page.nextOffsetId == null || page.records.isEmpty()) break
            offset = page.nextOffsetId
        }
        return out
    }

    // ------------------------------------------------------------------
    // Dedup — identical semantics to the legacy service
    // ------------------------------------------------------------------

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

    private suspend fun ensureStore() {
        recordStore.ensureReady(embeddingService.dimension)
        recordStore.ensureIndexes()
    }

    private companion object {
        const val TERM_WEIGHT = 1.0
        const val IDENTIFIER_WEIGHT = 2.0
        const val SCAN_PAGE = 200
        const val SCAN_CAP = 5_000
    }
}
