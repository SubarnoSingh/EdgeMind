package com.example.EdgeMemo.presentation.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.data.cloud.DefaultCloudAnswerCache
import com.example.EdgeMemo.data.cloud.DefaultCloudKnowledgeIngestor
import com.example.EdgeMemo.data.cloud.DefaultCloudKnowledgeWriter
import com.example.EdgeMemo.data.cloud.DefaultKnowledgeClassifier
import com.example.EdgeMemo.data.document.ContentResolverDocumentReader
import com.example.EdgeMemo.data.document.DocumentExtractorRegistry
import com.example.EdgeMemo.data.document.MarkdownDocumentExtractor
import com.example.EdgeMemo.data.document.PdfDocumentExtractor
import com.example.EdgeMemo.data.document.TxtDocumentExtractor
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.policy.DefaultPolicyEngine
import com.example.EdgeMemo.data.policy.DefaultRedactionService
import com.example.EdgeMemo.data.repository.DefaultMemoryRepository
import com.example.EdgeMemo.core.document.DocumentChunker
import com.example.EdgeMemo.domain.cloud.CacheCloudAnswerResult
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeBatch
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource
import com.example.EdgeMemo.domain.cloud.PullCloudKnowledgeUseCase
import com.example.EdgeMemo.domain.document.DocumentIngestionService
import com.example.EdgeMemo.domain.document.IngestDocumentUseCase
import com.example.EdgeMemo.domain.memory.CreateMemoryUseCase
import com.example.EdgeMemo.domain.memory.DeleteMemoryUseCase
import com.example.EdgeMemo.domain.memory.ListMemoriesUseCase
import com.example.EdgeMemo.domain.memory.SearchMemoriesUseCase
import com.example.EdgeMemo.native.qdrant.QdrantEdgeVectorStore
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The MemoryViewModel is Activity-scoped and shared across tabs; repository
 * mutations from other paths (the explicit cloud-answer "Save to memory",
 * conflict resolution) do not notify it. These tests pin the fix contract:
 * refreshing when the Memory screen becomes visible must surface ALL
 * repository mutations without duplicating rows or regressing
 * create/delete/pull behavior. Real Room + real qdrant-edge + real
 * embedding — no mocks of storage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MemoryViewModelRefreshTest {

    private lateinit var context: Context
    private lateinit var sandbox: File
    private lateinit var database: EdgeMindDatabase
    private lateinit var store: QdrantEdgeVectorStore
    private lateinit var repository: DefaultMemoryRepository
    private lateinit var cache: DefaultCloudAnswerCache
    private lateinit var mainDispatcher: TestDispatcher

    @Before
    fun setUp() {
        TestNativeLoader.ensureLoaded()
        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        context = ApplicationProvider.getApplicationContext()
        sandbox = File(context.cacheDir, "vm-refresh-${UUID.randomUUID()}").apply { mkdirs() }
        database = Room.databaseBuilder(context, EdgeMindDatabase::class.java, "vm-refresh-${UUID.randomUUID()}-db").build()
        store = QdrantEdgeVectorStore(File(sandbox, "qdrant"))
        repository = DefaultMemoryRepository(database.memoryDao(), store, FeatureHashingEmbeddingService())
        val classifier = DefaultKnowledgeClassifier()
        val writer = DefaultCloudKnowledgeWriter(database.memoryDao(), database, store, FeatureHashingEmbeddingService())
        cache = DefaultCloudAnswerCache(classifier, writer, database.memoryDao(), database.conflictDao())
    }

    @After
    fun tearDown() {
        runBlocking { store.close() }
        database.close()
        Dispatchers.resetMain()
        sandbox.deleteRecursively()
    }

    @Test
    fun savedCloudAnswerAppearsInMemoryListAfterRefresh() {
        val viewModel = viewModel()
        awaitUntil(viewModel, { viewModel.uiState.value.memoryCount == 0L }, "initial load")

        runBlocking {
            val result = cache.save(
                "What is the torque specification?",
                "The torque specification is 45 Nm.",
                "cloud-hub",
            )
            assertTrue("save must succeed", result is CacheCloudAnswerResult.Saved)
        }
        advanceUntilIdle()
        assertEquals(
            "before the Memory screen becomes visible the shared ViewModel has no signal",
            0L,
            viewModel.uiState.value.memoryCount,
        )

        // The screen becomes visible (tab switch) → refresh.
        viewModel.refresh()
        awaitUntil(viewModel, { viewModel.uiState.value.memoryCount == 1L }, "refresh surfaces the saved answer")

        val state = viewModel.uiState.value
        assertEquals(1, state.memories.size)
        val memory = state.memories.single()
        assertEquals(MemoryOrigin.CLOUD, memory.origin)
        assertEquals(MemoryType.CLOUD_KNOWLEDGE, memory.type)
        assertTrue(memory.title.startsWith(DefaultCloudAnswerCache.CLOUD_ANSWER_TITLE_PREFIX))
        assertEquals("data layer holds exactly one row", 1, runBlocking { repository.list() }.size)
    }

    @Test
    fun repeatedRefreshDoesNotDuplicateMemoryCards() {
        val viewModel = viewModel()
        awaitUntil(viewModel, { viewModel.uiState.value.memoryCount == 0L }, "initial load")

        viewModel.onTitleChange("P-101 repair")
        viewModel.onContentChange("P-101 seal failed due to cavitation; the seal was replaced.")
        viewModel.create()
        awaitUntil(viewModel, { viewModel.uiState.value.memories.size == 1 }, "create persisted")

        // Tab in and out several times.
        repeat(3) {
            viewModel.refresh()
            advanceUntilIdle()
        }
        awaitUntil(viewModel, { viewModel.uiState.value.memories.size == 1 }, "refresh keeps exactly one")

        val state = viewModel.uiState.value
        assertEquals(1, state.memories.size)
        assertEquals(1L, state.memoryCount)
        assertEquals("no duplicate card ids", 1, state.memories.map { it.memoryId }.distinct().size)
        assertEquals(1, runBlocking { repository.list() }.size)
    }

    @Test
    fun deleteAndCreateRemainCorrectAcrossRefreshes() {
        val viewModel = viewModel()
        awaitUntil(viewModel, { viewModel.uiState.value.memoryCount == 0L }, "initial load")

        viewModel.onTitleChange("Stale note")
        viewModel.onContentChange("remove me")
        viewModel.create()
        awaitUntil(viewModel, { viewModel.uiState.value.memories.size == 1 }, "create 1")
        val id = viewModel.uiState.value.memories[0].memoryId

        viewModel.delete(id)
        awaitUntil(viewModel, { viewModel.uiState.value.memories.isEmpty() }, "delete")

        viewModel.refresh()
        awaitUntil(viewModel, { viewModel.uiState.value.memoryCount == 0L }, "refresh after delete")
        assertEquals(0, viewModel.uiState.value.memories.size)

        viewModel.onTitleChange("New one")
        viewModel.onContentChange("hello again")
        viewModel.create()
        awaitUntil(viewModel, { viewModel.uiState.value.memories.size == 1 }, "create 2")

        viewModel.refresh()
        awaitUntil(viewModel, { viewModel.uiState.value.memories.size == 1 }, "refresh after create 2")
        assertEquals(1L, viewModel.uiState.value.memoryCount)
    }

    @Test
    fun cloudPullUpdatesListAndSurvivesFollowUpRefresh() {
        val remote = ScriptedRemote().script(
            CloudKnowledgeBatch(
                items = listOf(
                    item(
                        "torq-1",
                        "TORQUE-1",
                        "Torque the cap screws to 52 Nm.",
                        "h-52",
                        version = 5,
                        authority = "central-engineering",
                    ),
                ),
                nextCursor = null,
            ),
        )
        val viewModel = viewModel(remote)
        awaitUntil(viewModel, { viewModel.uiState.value.memoryCount == 0L }, "initial load")

        viewModel.pullCloud()
        awaitUntil(viewModel, { viewModel.uiState.value.pullStatus is CloudPullStatus.Success }, "pull completes")
        awaitUntil(viewModel, { viewModel.uiState.value.memories.size == 1 }, "pulled memory visible")

        val state = viewModel.uiState.value
        assertEquals(1L, state.memoryCount)
        assertEquals("Torque the cap screws to 52 Nm.", state.memories.single().content)

        viewModel.refresh()
        awaitUntil(viewModel, { viewModel.uiState.value.memories.size == 1 }, "refresh after pull")
        assertEquals(1, viewModel.uiState.value.memories.size)
    }

    private fun viewModel(remote: ScriptedRemote? = null): MemoryViewModel {
        val reader = ContentResolverDocumentReader(context)
        val ingestionService = DocumentIngestionService(
            reader = reader,
            registry = DocumentExtractorRegistry(
                listOf(
                    PdfDocumentExtractor(context),
                    MarkdownDocumentExtractor(),
                    TxtDocumentExtractor(),
                ),
            ),
            chunker = DocumentChunker(),
            repository = repository,
        )
        val pullUseCase = remote?.let { scripted ->
            PullCloudKnowledgeUseCase(
                DefaultCloudKnowledgeIngestor(
                    remote = scripted,
                    classifier = DefaultKnowledgeClassifier(),
                    writer = DefaultCloudKnowledgeWriter(database.memoryDao(), database, store, FeatureHashingEmbeddingService()),
                    memoryDao = database.memoryDao(),
                    conflictDao = database.conflictDao(),
                    cursorDao = database.cloudCursorDao(),
                ),
            )
        }
        return MemoryViewModel(
            createMemory = CreateMemoryUseCase(repository),
            listMemories = ListMemoriesUseCase(repository),
            searchMemories = SearchMemoriesUseCase(repository),
            deleteMemory = DeleteMemoryUseCase(repository),
            documentReader = reader,
            ingestDocument = IngestDocumentUseCase(ingestionService),
            policyEngine = DefaultPolicyEngine(DefaultRedactionService()),
            pullCloudKnowledge = pullUseCase,
        )
    }

    private fun advanceUntilIdle() {
        mainDispatcher.scheduler.advanceUntilIdle()
    }

    private fun awaitUntil(
        viewModel: MemoryViewModel,
        condition: () -> Boolean,
        message: String,
        timeoutMs: Long = 20_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.advanceUntilIdle()
            if (condition()) return
            Thread.sleep(10)
        }
        val repoSize = try {
            runBlocking { repository.list() }.size
        } catch (_: Exception) {
            -1
        }
        fail("$message (repository size=$repoSize, uiState=${viewModel.uiState.value})")
    }

    private class ScriptedRemote : CloudKnowledgeRemoteDataSource {
        private val queue = ArrayDeque<CloudKnowledgeBatch>()

        fun script(batch: CloudKnowledgeBatch): ScriptedRemote {
            queue.add(batch)
            return this
        }

        override suspend fun pullKnowledge(cursor: String?): CloudKnowledgeBatch =
            queue.removeFirstOrNull() ?: CloudKnowledgeBatch(emptyList())
    }

    private fun item(
        memoryId: String,
        subjectKey: String?,
        content: String,
        contentHash: String,
        version: Int = 1,
        supersedes: String? = null,
        tombstone: Boolean = false,
        authority: String? = null,
    ) = CloudKnowledgeItem(
        memoryId = pointId(memoryId),
        subjectKey = subjectKey,
        title = content.take(40),
        content = content,
        contentHash = contentHash,
        version = version,
        updatedAt = System.currentTimeMillis(),
        origin = "CLOUD",
        authority = authority,
        supersedes = supersedes?.let(::pointId),
        tombstone = tombstone,
        metadata = emptyMap(),
    )

    private fun pointId(label: String): String {
        val uuid = runCatching { UUID.fromString(label) }.getOrNull()
        return uuid?.toString() ?: cloudId(label)
    }

    private fun cloudId(label: String): String =
        UUID.nameUUIDFromBytes(label.toByteArray(Charsets.UTF_8)).toString()
}
