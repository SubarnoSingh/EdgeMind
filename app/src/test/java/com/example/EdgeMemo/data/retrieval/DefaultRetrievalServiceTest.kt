package com.example.EdgeMemo.data.retrieval

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.ai.embedding.EmbeddingService
import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryMetadataKeys
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.retrieval.RetrievalOptions
import com.example.EdgeMemo.core.retrieval.RetrievalQuery
import com.example.EdgeMemo.core.retrieval.RetrievalService
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.repository.DefaultMemoryRepository
import com.example.EdgeMemo.native.qdrant.LocalVectorStore
import com.example.EdgeMemo.native.qdrant.QdrantEdgeVectorStore
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 4 retrieval integration tests: real Room, real qdrant-edge .so, real
 * embedding. No mocks of storage or vectors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DefaultRetrievalServiceTest {

    private lateinit var context: Context
    private lateinit var sandbox: File

    @Before
    fun setUp() {
        TestNativeLoader.ensureLoaded()
        context = ApplicationProvider.getApplicationContext()
        sandbox = File(context.cacheDir, "phase4-retrieval-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    @Test
    fun denseRetrievalResolvesMetadataAndRanks() = runBlocking {
        val stack = newStack()
        try {
            val relevant = stack.repository.create(
                CreateMemoryInput(
                    title = "P-101 pump seal",
                    content = "The P-101 seal failed due to cavitation. The seal was replaced during the outage.",
                    type = MemoryType.REPAIR,
                    tags = listOf("p101"),
                ),
            )
            stack.repository.create(
                CreateMemoryInput(
                    title = "Weather notes",
                    content = "The forecast calls for heavy rain on Tuesday across the coastal region.",
                    type = MemoryType.OBSERVATION,
                ),
            )

            val result = stack.retrieval.retrieve(RetrievalQuery(text = "pump seal cavitation", limit = 3))

            assertTrue(result.evidence.isNotEmpty())
            val top = result.evidence.first()
            assertEquals(relevant.memoryId, top.memory.memoryId)
            assertEquals(1, top.rank)
            assertTrue(top.denseScore != null && top.denseScore!! > 0.0)
            assertTrue(top.score > 0.0)
            assertEquals("P-101 pump seal", top.memory.title)
        } finally {
            stack.close()
        }
    }

    @Test
    fun exactIdentifierIsRetrievedThroughKeywordPath() = runBlocking {
        val stack = newStack()
        try {
            val created = stack.repository.create(
                CreateMemoryInput(
                    title = "Bearing spec",
                    content = "Use the SKF-6205 deep groove ball bearing for the pump motor.",
                    type = MemoryType.PROCEDURE,
                ),
            )

            val result = stack.retrieval.retrieve(RetrievalQuery(text = "SKF-6205", limit = 3))

            assertTrue(result.evidence.isNotEmpty())
            val hit = result.evidence.first { it.memory.memoryId == created.memoryId }
            assertTrue(hit.keywordScore != null && hit.keywordScore!! > 0.0)
            assertTrue(hit.matchedTerms.any { it.lowercase().contains("skf-6205") })
        } finally {
            stack.close()
        }
    }

    @Test
    fun tombstonedMemoryIsExcluded() = runBlocking {
        val stack = newStack()
        try {
            val active = stack.repository.create(
                CreateMemoryInput(title = "Torque spec", content = "Torque the flange bolts to 120 Nm."),
            )
            val removed = stack.repository.create(
                CreateMemoryInput(title = "Old torque note", content = "Torque the flange bolts to 90 Nm."),
            )
            stack.repository.update(removed.copy(tombstone = true))

            val result = stack.retrieval.retrieve(RetrievalQuery(text = "torque flange bolts", limit = 5))

            val ids = result.evidence.map { it.memory.memoryId }
            assertTrue(ids.contains(active.memoryId))
            assertTrue("tombstoned memory must not be returned", removed.memoryId !in ids)
        } finally {
            stack.close()
        }
    }

    @Test
    fun supersededMemoryIsExcludedButSuccessorRemains() = runBlocking {
        val stack = newStack()
        try {
            val old = stack.repository.create(
                CreateMemoryInput(
                    title = "Procedure rev 3",
                    content = "Flush the P-101 pump daily.",
                    type = MemoryType.PROCEDURE,
                ),
            )
            val current = stack.repository.create(
                CreateMemoryInput(title = "Procedure rev 4", content = "Flush the P-101 pump weekly instead.", type = MemoryType.PROCEDURE),
            )
            stack.repository.update(old.copy(supersedes = null))
            stack.repository.update(
                current.copy(supersedes = old.memoryId),
            )

            val result = stack.retrieval.retrieve(RetrievalQuery(text = "flush p-101 pump", limit = 5))

            val ids = result.evidence.map { it.memory.memoryId }
            assertTrue("successor must be returned", ids.contains(current.memoryId))
            assertTrue("superseded memory must be excluded", old.memoryId !in ids)
        } finally {
            stack.close()
        }
    }

    @Test
    fun duplicateContentIsDeduplicated() = runBlocking {
        val stack = newStack()
        try {
            stack.repository.create(CreateMemoryInput(title = "Same", content = "The coupler aligns with the shaft keyway."))
            stack.repository.create(CreateMemoryInput(title = "Same", content = "The coupler aligns with the shaft keyway."))

            val result = stack.retrieval.retrieve(RetrievalQuery(text = "coupler shaft keyway", limit = 5))

            val ids = result.evidence.map { it.memory.memoryId }
            assertEquals("exact duplicate content must collapse to one evidence", 1, ids.size)
        } finally {
            stack.close()
        }
    }

    @Test
    fun chunkSourceMetadataIsPreservedOnEvidence() = runBlocking {
        val stack = newStack()
        try {
            val created = stack.repository.create(
                CreateMemoryInput(
                    title = "Manual chunk 1",
                    content = "Seal inspection steps for the P-101 pump.",
                    type = MemoryType.DOCUMENT,
                    chunkId = "doc-1#0",
                    metadata = mapOf(
                        MemoryMetadataKeys.DOCUMENT_ID to "doc-1",
                        MemoryMetadataKeys.PAGE to "17",
                        MemoryMetadataKeys.SECTION to "Seal Inspection",
                        MemoryMetadataKeys.CHUNK_INDEX to "0",
                    ),
                ),
            )

            val result = stack.retrieval.retrieve(RetrievalQuery(text = "p-101 seal inspection", limit = 3))

            val hit = result.evidence.first()
            assertEquals(created.chunkId, hit.memory.chunkId)
            assertEquals("17", hit.memory.metadata[MemoryMetadataKeys.PAGE])
            assertEquals("Seal Inspection", hit.memory.metadata[MemoryMetadataKeys.SECTION])
            assertNotNull(hit.memory.source)
        } finally {
            stack.close()
        }
    }

    @Test
    fun retrievalSurvivesRestart() = runBlocking {
        val dbName = "phase4-${UUID.randomUUID()}"
        val qdrantDir = File(sandbox, "qdrant-restart")
        val stack = newStack(dbName = dbName, qdrantDir = qdrantDir)
        val created = stack.repository.create(
            CreateMemoryInput(
                title = "Encoder service",
                content = "The E-4417 encoder requires recalibration after every service.",
                type = MemoryType.PROCEDURE,
            ),
        )
        stack.close()

        val reopened = newStack(dbName = dbName, qdrantDir = qdrantDir)
        try {
            val result = reopened.retrieval.retrieve(RetrievalQuery(text = "e-4417 encoder recalibration", limit = 3))
            assertTrue(result.evidence.isNotEmpty())
            assertTrue(result.evidence.any { it.memory.memoryId == created.memoryId })
        } finally {
            reopened.close()
        }
    }

    @Test
    fun emptyQueryFailsWithInvalidInput() = runBlocking {
        val stack = newStack()
        try {
            try {
                stack.retrieval.retrieve(RetrievalQuery(text = "   "))
                fail("expected InvalidInput")
            } catch (e: EdgeError.InvalidInput) {
                assertTrue(e.message!!.contains("empty"))
            }
        } finally {
            stack.close()
        }
    }

    @Test
    fun denseOnlyRetrievalStillWorksWhenHybridDisabled() = runBlocking {
        val stack = newStack()
        try {
            stack.repository.create(
                CreateMemoryInput(
                    title = "Filter maintenance",
                    content = "Inspect the E-4417 inlet filter monthly.",
                ),
            )
            val result = stack.retrieval.retrieve(
                RetrievalQuery(
                    text = "inlet filter monthly",
                    limit = 3,
                    options = RetrievalOptions(useHybrid = false),
                ),
            )
            assertTrue(result.evidence.isNotEmpty())
        } finally {
            stack.close()
        }
    }

    private class Stack(
        val database: EdgeMindDatabase,
        val store: LocalVectorStore,
        val repository: DefaultMemoryRepository,
        val retrieval: RetrievalService,
    ) {
        fun close() {
            runBlocking { store.close() }
            database.close()
        }
    }

    private fun newStack(
        dbName: String = "phase4-${UUID.randomUUID()}",
        qdrantDir: File = File(sandbox, "qdrant-${UUID.randomUUID()}"),
        embedding: EmbeddingService = FeatureHashingEmbeddingService(),
    ): Stack {
        val database = Room.databaseBuilder(context, EdgeMindDatabase::class.java, dbName).build()
        val store = QdrantEdgeVectorStore(qdrantDir)
        val repository = DefaultMemoryRepository(database.memoryDao(), store, embedding)
        val retrieval = DefaultRetrievalService(
            embeddingService = embedding,
            vectorStore = store,
            dao = database.memoryDao(),
            keywordRetriever = KeywordRetriever(database.memoryDao()),
        )
        return Stack(database, store, repository, retrieval)
    }
}