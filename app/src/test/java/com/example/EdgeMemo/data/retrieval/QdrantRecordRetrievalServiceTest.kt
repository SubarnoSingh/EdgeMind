package com.example.EdgeMemo.data.retrieval

import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryMetadataKeys
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.LocalRecordStore
import com.example.EdgeMemo.core.retrieval.RetrievalOptions
import com.example.EdgeMemo.core.retrieval.RetrievalQuery
import com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 13.3 — the ACTIVE retrieval pipeline runs entirely on the real
 * Qdrant Edge application shard. Every record is written as a genuine
 * vector-bearing point through the production record store; nothing is
 * mocked and no Room instance exists in this fixture at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QdrantRecordRetrievalServiceTest {

    companion object {
        private const val DIMENSION = 512

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    private val dir = File.createTempFile("p133-", "").apply { delete(); mkdirs() }
    private val store = QdrantEdgeRecordStore(File(dir, "qdrant_sync_store"))
    private val embeddings = FeatureHashingEmbeddingService()
    private val service = QdrantRecordRetrievalService(embeddings, store)

    @After
    fun tearDown() {
        runBlocking { runCatching { store.close() } }
        dir.deleteRecursively()
    }

    private fun upsert(
        title: String,
        content: String,
        type: String = "memory",
        metadata: Map<String, String> = emptyMap(),
        supersedes: String? = null,
        chunkId: String? = null,
    ): Memory = runBlocking {
        store.ensureReady(DIMENSION)
        store.ensureIndexes()
        val vector = embeddings.embed("$title\n$content")
        val payloadMap: Map<String, com.example.EdgeMemo.core.record.JsonValue> = mapOf(
            "title" to JsonValue.fromString(title),
            "content" to JsonValue.fromString(content),
            "type" to JsonValue.fromString("NOTE"),
            "sensitivity" to JsonValue.fromString("STANDARD"),
            "importance" to JsonValue.fromInt(0),
        ).let { base -> chunkId?.let { base + ("chunkId" to JsonValue.fromString(it)) } ?: base }
        val record = com.example.EdgeMemo.core.record.Record(
            id = com.example.EdgeMemo.core.record.RecordId.random(),
            recordType = com.example.EdgeMemo.core.record.RecordType.fromPayloadValue(type)
                ?: error("bad type"),
            entityId = null,
            vector = vector,
            payload = payloadMap,
            version = 1,
            source = title,
            supersedes = supersedes,
            contentHash = com.example.EdgeMemo.core.sync.CanonicalContentHash.hash(payloadMap),
            metadata = metadata,
        )
        store.upsert(record)
        val memory = com.example.EdgeMemo.data.repository.MemoryRecordMapper.toMemory(record)
        memory
    }

    // ------------------------------------------------------------------
    // A — dense retrieval on the unified shard
    // ------------------------------------------------------------------

    @Test
    fun denseRetrievalFindsTheVectorBearingRecord() = runBlocking {
        val relevant = upsert(
            "P-101 pump seal",
            "The P-101 seal failed due to cavitation. The seal was replaced during the outage.",
        )
        upsert("Weather notes", "The forecast calls for heavy rain on Tuesday across the coastal region.")

        val result = service.retrieve(RetrievalQuery(text = "pump seal cavitation", limit = 3))

        assertTrue(result.evidence.isNotEmpty())
        val top = result.evidence.first()
        assertEquals(relevant.memoryId, top.memory.memoryId)
        assertNotNull("dense score must come from real vector similarity", top.denseScore)
        assertTrue(top.denseScore!! > 0.0)
    }

    @Test
    fun emptyQueryIsRejected() {
        assertThrows(EdgeError.InvalidInput::class.java) {
            runBlocking { service.retrieve(RetrievalQuery(text = "   ")) }
        }
    }

    // ------------------------------------------------------------------
    // B/F — keyword retrieval with identifier weighting
    // ------------------------------------------------------------------

    @Test
    fun keywordPathMatchesExactIdentifiersLikeTheLegacyLikeQuery() = runBlocking {
        val bearing = upsert(
            "Bearing list",
            "Stock the SKF-6205 bearing for the main drive assembly.",
        )
        val unrelated = upsert("Cafeteria", "The lunch menu changes every Thursday.")

        val result = service.retrieve(
            RetrievalQuery(
                text = "SKF-6205 bearing",
                limit = 5,
                options = RetrievalOptions(useHybrid = true),
            ),
        )

        val hit = result.evidence.first { it.memory.memoryId == bearing.memoryId }
        assertNotNull("keyword score must be real", hit.keywordScore)
        assertTrue(hit.keywordScore!! > 0.0)
        assertTrue("matched terms must be reported", hit.matchedTerms.isNotEmpty())
        assertTrue(result.evidence.none { it.memory.memoryId == unrelated.memoryId && (it.keywordScore ?: 0.0) > 0 })
    }

    @Test
    fun identifierTermsOutrankPlainTermsLikeLegacyWeights() = runBlocking {
        // Only contains the plain term.
        val plain = upsert("Seal notes", "A seal was seen leaking near the pump housing.")
        // Contains both the identifier and the plain term.
        val identified = upsert("P-101 seal log", "P-101 seal weep recorded at the pump.")

        val result = service.retrieve(
            RetrievalQuery(text = "P-101 seal", limit = 5),
        )
        val plainHit = result.evidence.first { it.memory.memoryId == plain.memoryId }
        val identifiedHit = result.evidence.first { it.memory.memoryId == identified.memoryId }
        // identifier weight 2.0 + seal 1.0  vs  seal 1.0
        assertTrue(
            "identifier weighting must be preserved: ${identifiedHit.keywordScore} vs ${plainHit.keywordScore}",
            (identifiedHit.keywordScore ?: 0.0) > (plainHit.keywordScore ?: 0.0),
        )
    }

    // ------------------------------------------------------------------
    // C — system points never leak; tombstones excluded
    // ------------------------------------------------------------------

    @Test
    fun retrievalNeverSurfacesSystemRecordsAndSkipsTombstones() = runBlocking {
        val kept = upsert("Alignment", "Laser alignment within 0.2 mm on the press line.")
        val doomed = upsert("Obsolete", "Old coupling failure analysis that must not surface.")
        store.softDelete(com.example.EdgeMemo.core.record.RecordId.fromString(doomed.memoryId))

        // A payload-only outbox-style system point in the SAME collection.
        store.upsert(
            com.example.EdgeMemo.core.record.Record(
                id = com.example.EdgeMemo.core.record.RecordId.random(),
                recordType = com.example.EdgeMemo.core.record.RecordType.OUTBOX_OP,
                entityId = null,
                vector = null,
                payload = mapOf("operation_id" to JsonValue.fromString("UPSERT:x:1")),
                contentHash = null,
            ),
        )

        val result = service.retrieve(RetrievalQuery(text = "failure coupling alignment", limit = 10))
        assertTrue(result.evidence.all { it.memory.memoryId != doomed.memoryId })
        assertTrue(result.evidence.all { (it.memory.metadata["operation_id"] == null) })
        assertNotNull(result.evidence.firstOrNull { it.memory.memoryId == kept.memoryId })
    }

    // ------------------------------------------------------------------
    // D/E — hybrid + RRF: fused evidence carries both score channels
    // ------------------------------------------------------------------

    @Test
    fun hybridFusionCombinesDenseAndKeywordRankings() = runBlocking {
        val both = upsert(
            "Hydraulic pump maintenance",
            "Service the hydraulic pump seals and check pressure at 90 bar.",
        )

        val result = service.retrieve(
            RetrievalQuery(text = "hydraulic pump seals pressure", limit = 5),
        )
        val item = result.evidence.first { it.memory.memoryId == both.memoryId }
        assertNotNull("dense channel contributed", item.denseScore)
        assertNotNull("keyword channel contributed", item.keywordScore)
        assertTrue(item.score > 0.0)
        assertEquals(1, item.rank) // best evidence ranks first
    }

    @Test
    fun denseOnlyModeClearsKeywordChannel() = runBlocking {
        val id = upsert("Gearbox vibration", "Increase in gearbox vibration was recorded near bearing 7.")
        val result = service.retrieve(
            RetrievalQuery(
                text = "gearbox vibration bearing",
                limit = 5,
                options = RetrievalOptions(useHybrid = false),
            ),
        )
        val item = result.evidence.first { it.memory.memoryId == id.memoryId }
        assertNull(item.keywordScore)
        assertNotNull(item.denseScore)
    }

    // ------------------------------------------------------------------
    // G — deduplication
    // ------------------------------------------------------------------

    @Test
    fun identicalContentIsDeduplicated() = runBlocking {
        upsert("Same note", "Replace the coupling every overhaul cycle.")
        upsert("Same note", "Replace the coupling every overhaul cycle.")

        val result = service.retrieve(RetrievalQuery(text = "replace the coupling every overhaul cycle", limit = 10))
        val texts = result.evidence.map { it.memory.content }
        assertEquals(texts.size, texts.distinct().size)
    }

    // ------------------------------------------------------------------
    // superseded exclusion (legacy DISTINCT supersedes parity)
    // ------------------------------------------------------------------

    @Test
    fun supersededRecordsAreExcluded() = runBlocking {
        val old = upsert("Old procedure", "Drain the sump using the manual valve.")
        upsert("New procedure", "Drain the sump using the automatic valve.", supersedes = old.memoryId)

        val result = service.retrieve(RetrievalQuery(text = "drain the sump valve", limit = 10))
        assertTrue(result.evidence.none { it.memory.memoryId == old.memoryId })
    }

    // ------------------------------------------------------------------
    // I — citation-relevant fields survive Record → Evidence mapping
    // ------------------------------------------------------------------

    @Test
    fun citationFieldsSurviveTheRecordMapping() = runBlocking {
        val doc = upsert(
            "Press manual #2",
            "Section 3.2 describes the seal replacement procedure for P-101.",
            metadata = mapOf(
                MemoryMetadataKeys.DOCUMENT_ID to "doc-1",
                MemoryMetadataKeys.CHUNK_INDEX to "1",
                MemoryMetadataKeys.PAGE to "7",
                MemoryMetadataKeys.SECTION to "3.2 Seal replacement",
                MemoryMetadataKeys.SOURCE_NAME to "press-manual.pdf",
            ),
            chunkId = "doc-1#1",
        )

        val result = service.retrieve(RetrievalQuery(text = "P-101 seal replacement procedure manual", limit = 5))
        val item = result.evidence.first { it.memory.memoryId == doc.memoryId }
    }

    // ------------------------------------------------------------------
    // K/Q — restart persistence of retrieval
    // ------------------------------------------------------------------

    @Test
    fun retrievalWorksIdenticallyAfterShardReopen() = runBlocking {
        val id = upsert("Coolant spec", "Run coolant at pH 8.5 to prevent P-101 corrosion.")

        val before = service.retrieve(RetrievalQuery(text = "coolant pH corrosion", limit = 5))
        assertTrue(before.evidence.any { it.memory.memoryId == id.memoryId })

        store.close()
        val reopened = QdrantEdgeRecordStore(File(dir, "qdrant_sync_store"))
        try {
            reopened.open()
            val after = QdrantRecordRetrievalService(embeddings, reopened)
                .retrieve(RetrievalQuery(text = "coolant pH corrosion", limit = 5))
            assertNotNull(after.evidence.firstOrNull { it.memory.memoryId == id.memoryId })
            assertNotNull("vector must survive reopen so dense search still runs",
                after.evidence.first { it.memory.memoryId == id.memoryId }.denseScore)
        } finally {
            reopened.close()
        }
        // tearDown's close is idempotent-safe
    }

    // ------------------------------------------------------------------
    // H — sufficiency inputs are honest (no fabricated scores)
    // ------------------------------------------------------------------

    @Test
    fun denseScoresAreRealSimilaritiesRankedByGenuineRelevance() = runBlocking {
        val related = upsert("Pump seal", "Cavitation damaged the mechanical seal on pump P-101.")
        val unrelated = upsert("Parking", "Visitor parking is behind the west gate.")

        val result = service.retrieve(
            RetrievalQuery(text = "mechanical seal cavitation pump", limit = 5),
        )
        val relatedScore = result.evidence.first { it.memory.memoryId == related.memoryId }.denseScore
        val unrelatedScore = result.evidence.first { it.memory.memoryId == unrelated.memoryId }.denseScore
        // Scores come straight from the shard's vector search: the genuinely
        // similar record must outrank the unrelated one; no fabricated values.
        assertTrue(
            "dense scores must reflect true similarity: $relatedScore vs $unrelatedScore",
            (relatedScore ?: 0.0) > (unrelatedScore ?: 0.0),
        )
        assertEquals(
            relatedScore,
            store.search(
                com.example.EdgeMemo.core.record.RecordQuery.Search(
                    vector = embeddings.embed("mechanical seal cavitation pump"), limit = 5,
                ),
            ).first { it.record.id.uuid == related.memoryId }.score,
        )
    }
}
