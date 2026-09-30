package com.example.EdgeMemo.data.cloud

import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.record.JsonString
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordQuery
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.record.SyncState
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore
import com.example.EdgeMemo.data.local.sync.DefaultQdrantSyncEngine
import com.example.EdgeMemo.data.local.sync.QdrantChangeDetector
import com.example.EdgeMemo.data.local.sync.QdrantSyncOperationStore
import com.example.EdgeMemo.data.sync.QdrantNativeCloudKnowledgeIngestor
import com.example.EdgeMemo.data.sync.UnimplementedQdrantSyncRemote
import com.example.EdgeMemo.domain.cloud.CacheCloudAnswerResult
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeBatch
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 13.4 — the cloud-answer localization now writes the Qdrant-native
 * shard through the same frozen §12 single-item pipeline as cloud pull.
 * Legacy Phase 7/8 decision semantics are pinned unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QdrantCloudAnswerCacheTest {

    companion object {
        private const val DIMENSION = 512

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    private val dir = File.createTempFile("p134-cache-", "").apply { delete(); mkdirs() }
    private val store = QdrantEdgeRecordStore(File(dir, "qdrant_sync_store"))
    private val embeddings = FeatureHashingEmbeddingService()
    private val operations = QdrantSyncOperationStore(store)
    private val engine = DefaultQdrantSyncEngine(
        recordStore = store,
        operationStore = operations,
        detector = QdrantChangeDetector(store, operations),
        remote = UnimplementedQdrantSyncRemote(),
        cloudKnowledge = object : CloudKnowledgeRemoteDataSource {
            override suspend fun pullKnowledge(cursor: String?) = CloudKnowledgeBatch(emptyList())
        },
        cloudEmbedding = { item ->
            runCatching { embeddings.embed("${item.title}\n${item.content}") }.getOrNull()
        },
    )
    private val cache = QdrantCloudAnswerCache(store, engine, DefaultKnowledgeClassifier())

    @After
    fun tearDown() {
        runBlocking { runCatching { store.close() } }
        dir.deleteRecursively()
    }

    @org.junit.Before
    fun open() {
        runBlocking {
            store.ensureReady(DIMENSION)
            store.ensureIndexes()
        }
    }

    private fun records() = runBlocking {
        store.scroll(RecordQuery.allActive(limit = 50)).records
    }

    @Test
    fun savedAnswerLandsInApplicationShardAsCloudKnowledgeWithoutEcho() {
        val result = runBlocking {
            cache.save("Why does the press alarm at 40 bar?", "The relief valve is set too low.", "PLANT-ENG")
        }
        assertTrue(result is CacheCloudAnswerResult.Saved)
        val memory = (result as CacheCloudAnswerResult.Saved).memory

        val stored = runBlocking { store.get(RecordId.fromString(memory.memoryId))!! }
        assertEquals(com.example.EdgeMemo.core.record.RecordOrigin.CLOUD, stored.origin)
        assertEquals(SyncState.SYNCED, stored.syncState)
        assertEquals("The relief valve is set too low.",
            (stored.payload.getValue("content") as JsonString).value)
        assertEquals("Why does the press alarm at 40 bar?",
            stored.metadata["question"])
        assertEquals("PLANT-ENG", stored.authority)

        // Echo immunity: cloud-origin + watermarked ⇒ zero operations ever.
        assertEquals(0L, runBlocking { operations.countByState(OutboxOperationState.PENDING) })
    }

    @Test
    fun identicalAnswerIsAlreadyPresentWithoutSecondWrite() {
        runBlocking { cache.save("Setpoint?", "Set it to 12.", null) }
        val countAfterFirst = records().size
        val second = runBlocking { cache.save("Setpoint?", "Set it to 12.", null) }
        assertTrue("expected $second", second is CacheCloudAnswerResult.AlreadyPresent)
        assertEquals(countAfterFirst, records().size)
    }

    @Test
    fun divergentAnswerRefusesOverwriteAndRecordsQdrantConflict() {
        runBlocking { cache.save("Setpoint?", "Set it to 12.", null) }
        val divergent = runBlocking { cache.save("Setpoint?", "Actually 15 is correct.", null) }
        assertTrue(divergent is CacheCloudAnswerResult.ConflictPrevented)

        // Original local answer survives untouched…
        assertEquals(
            "Set it to 12.",
            (records().first { it.subjectKey == subjectOf("Setpoint?") }
                .payload.getValue("content") as JsonString).value,
        )
        // …and the divergence is durable as a Qdrant conflict point.
        val conflicts = runBlocking {
            store.scroll(RecordQuery.Scroll(limit = 10, recordTypes = setOf(RecordType.CONFLICT))).records
        }
        assertEquals(1, conflicts.size)
        assertEquals("UNRESOLVED", (conflicts.first().payload.getValue("state") as JsonString).value)
    }

    @Test
    fun blankAndOversizedAnswersAreRejectedWithoutWrites() {
        assertThrows(EdgeError.InvalidInput::class.java) {
            runBlocking { cache.save("q", "  ", null) }
        }
        assertThrows(EdgeError.InvalidInput::class.java) {
            runBlocking { cache.save(" ", "answer", null) }
        }
        assertThrows(EdgeError.InvalidInput::class.java) {
            runBlocking { cache.save("q", "x".repeat(8_001), null) }
        }
        assertTrue(records().isEmpty())
    }

    @Test
    fun savedAnswerIsRetrievableByTheActivePipeline() {
        runBlocking { cache.save("Gearbox water limit", "Water must stay below 0.2 percent.", null) }
        val evidence = runBlocking {
            com.example.EdgeMemo.data.retrieval.QdrantRecordRetrievalService(embeddings, store)
                .retrieve(
                    com.example.EdgeMemo.core.retrieval.RetrievalQuery(
                        text = "gearbox water limit percent", limit = 5,
                    ),
                )
        }
        assertNotNull(evidence.evidence.firstOrNull { it.memory.title.startsWith("Cloud answer:") })
    }

    private fun subjectOf(question: String): String {
        val normalized = com.example.EdgeMemo.core.retrieval.QueryNormalizer.normalize(question)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return DefaultCloudAnswerCache.SUBJECT_PREFIX +
            digest.joinToString("") { "%02x".format(it) }
    }
}
