package com.example.EdgeMemo.di

import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore
import com.example.EdgeMemo.data.local.sync.DefaultQdrantSyncEngine
import com.example.EdgeMemo.testing.TestNativeLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 12B.13 — the integration gap this repository actually hit: the
 * Qdrant-native sync stack must be reachable from the production dependency
 * graph, not orphaned next to it. These tests pin the AppContainer wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppContainerQdrantSyncWiringTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    @Test
    fun appContainerExposesTheProductionQdrantSyncRuntime() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val container = AppContainer(context)

        val runtime = container.qdrantSyncRuntime
        assertTrue(
            "production engine must be the Qdrant-native DefaultQdrantSyncEngine",
            runtime.engine is DefaultQdrantSyncEngine,
        )
        assertTrue(
            "runtime store must be the real JNI-backed Qdrant Edge record store",
            runtime.recordStore is QdrantEdgeRecordStore,
        )
        assertSame(
            "exactly one runtime per process — no second shard owner",
            runtime,
            container.qdrantSyncRuntime,
        )
    }

    @Test
    fun runtimeUsesTheProductionEmbeddingDimension() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val container = AppContainer(context)
        org.junit.Assert.assertEquals(
            container.embeddingService.dimension,
            container.qdrantSyncRuntime.dimension,
        )
    }

    @Test
    fun activeMemoryGraphIsQdrantNativeAndRoomReceivesNoWrites() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val container = AppContainer(context)

        // Author through the REAL production graph.
        val created = container.createMemory(
            com.example.EdgeMemo.core.model.CreateMemoryInput(
                title = "Cutover probe",
                content = "Verify the production write path lands in Qdrant only",
                type = com.example.EdgeMemo.core.model.MemoryType.NOTE,
                userSyncChoice = com.example.EdgeMemo.core.model.SyncDecision.SYNC,
            ),
        )

        // Read back through the Qdrant-native repository.
        org.junit.Assert.assertEquals(
            "Cutover probe",
            container.memoryRepository.get(created.memoryId)?.title,
        )
        // A deterministic Qdrant-native operation exists for the mutation.
        val pending = container.qdrantSyncOperations.countByState(
            com.example.EdgeMemo.core.sync.OutboxOperationState.PENDING,
        )
        org.junit.Assert.assertEquals(1L, pending)

        // The frozen Room rollback database — same process, same files —
        // received NO application writes.
        val rollbackDb = androidx.room.Room
            .databaseBuilder(context, com.example.EdgeMemo.data.local.room.EdgeMindDatabase::class.java, "edge-memory.db")
            .build()
        try {
            org.junit.Assert.assertEquals(0L, rollbackDb.memoryDao().count())
            org.junit.Assert.assertEquals(0L, rollbackDb.syncOutboxDao().countAll())
        } finally {
            rollbackDb.close()
        }
    }

    @Test
    fun productionGraphRetrievesAndAnswersNewRecordsWithoutRoomOrLegacyShard() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val container = AppContainer(context)

        val created = container.createMemory(
            com.example.EdgeMemo.core.model.CreateMemoryInput(
                title = "P-101 seal failure",
                content = "The P-101 mechanical seal failed after a dry-run event on line A.",
                type = com.example.EdgeMemo.core.model.MemoryType.REPAIR,
            ),
        )

        // The ACTIVE container retrieval sees the brand-new record densely
        // and by identifier keyword — proving the ask/Memory search path is
        // on the application shard, not Room/local_qdrant.
        val result = container.retrieveMemories(
            com.example.EdgeMemo.core.retrieval.RetrievalQuery(
                text = "P-101 mechanical seal dry run", limit = 5,
            ),
        )
        val evidence = result.evidence.firstOrNull { it.memory.memoryId == created.memoryId }
        org.junit.Assert.assertNotNull("new record must be immediately retrievable", evidence)
        org.junit.Assert.assertNotNull(evidence!!.denseScore)
        org.junit.Assert.assertNotNull(evidence.keywordScore)

        // The production RAG graph (escalating → default → Qdrant retrieval)
        // answers locally with truthful citations.
        val response = container.askQuestion(
            com.example.EdgeMemo.core.rag.RagRequest(question = "Why did the P-101 seal fail?"),
        )
        org.junit.Assert.assertEquals(
            com.example.EdgeMemo.core.rag.AnswerStatus.ANSWERED, response.status,
        )
        org.junit.Assert.assertTrue(response.sources.any { it.memoryId == created.memoryId })

        // Room stayed untouched by the whole authoring→retrieval→RAG flow.
        val rollbackDb = androidx.room.Room
            .databaseBuilder(context, com.example.EdgeMemo.data.local.room.EdgeMindDatabase::class.java, "edge-memory.db")
            .build()
        try {
            org.junit.Assert.assertEquals(0L, rollbackDb.memoryDao().count())
        } finally {
            rollbackDb.close()
        }
    }

    @Test
    fun productionGraphRunsTheFullCycleWithoutRoomOrASecondShard() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val container = AppContainer(context)

        // Author → list → retrieve → RAG → sync status → conflicts: the whole
        // active application surface, through the production container only.
        val created = container.createMemory(
            com.example.EdgeMemo.core.model.CreateMemoryInput(
                title = "P-101 gearbox",
                content = "P-101 gearbox oil water content reached 0.3 percent.",
                type = com.example.EdgeMemo.core.model.MemoryType.OBSERVATION,
                userSyncChoice = com.example.EdgeMemo.core.model.SyncDecision.SYNC,
            ),
        )
        org.junit.Assert.assertTrue(container.listMemories().any { it.memoryId == created.memoryId })
        val evidence = container.retrieveMemories(
            com.example.EdgeMemo.core.retrieval.RetrievalQuery(text = "P-101 gearbox water content", limit = 5),
        )
        org.junit.Assert.assertNotNull(evidence.evidence.firstOrNull { it.memory.memoryId == created.memoryId })
        val answer = container.askQuestion(
            com.example.EdgeMemo.core.rag.RagRequest(question = "What was the P-101 gearbox water content?"),
        )
        org.junit.Assert.assertEquals(
            com.example.EdgeMemo.core.rag.AnswerStatus.ANSWERED, answer.status,
        )
        org.junit.Assert.assertEquals(1L, container.syncStatusReader.outboxCounts().pending)
        org.junit.Assert.assertTrue(container.listConflicts().isEmpty())

        // Architecture proof: the ONLY Qdrant shard the runtime created is
        // the application shard; no local_qdrant, no Room database file.
        val shardDirs = context.filesDir.listFiles()?.filter { it.isDirectory && it.name.contains("qdrant") }
            ?.map { it.name } ?: emptyList()
        org.junit.Assert.assertEquals(listOf("qdrant_sync_store"), shardDirs.sorted())
        org.junit.Assert.assertFalse(
            "Room must never be opened by normal operation",
            context.getDatabasePath("edge-memory.db").exists(),
        )
    }
}
