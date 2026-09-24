package com.example.EdgeMemo.presentation.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.core.document.DocumentChunker
import com.example.EdgeMemo.data.document.ContentResolverDocumentReader
import com.example.EdgeMemo.data.document.DocumentExtractorRegistry
import com.example.EdgeMemo.data.document.MarkdownDocumentExtractor
import com.example.EdgeMemo.data.document.PdfDocumentExtractor
import com.example.EdgeMemo.data.document.TxtDocumentExtractor
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.repository.DefaultMemoryRepository
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MemoryViewModelTest {

    private lateinit var context: Context
    private lateinit var sandbox: File
    private lateinit var database: EdgeMindDatabase
    private lateinit var store: QdrantEdgeVectorStore
    private lateinit var repository: DefaultMemoryRepository
    private lateinit var mainDispatcher: TestDispatcher

    @Before
    fun setUp() {
        TestNativeLoader.ensureLoaded()
        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        context = ApplicationProvider.getApplicationContext()
        sandbox = File(context.cacheDir, "vm-${UUID.randomUUID()}").apply { mkdirs() }
        database = Room.databaseBuilder(context, EdgeMindDatabase::class.java, "vm-${UUID.randomUUID()}-db").build()
        store = QdrantEdgeVectorStore(File(sandbox, "qdrant"))
        repository = DefaultMemoryRepository(database.memoryDao(), store, FeatureHashingEmbeddingService())
    }

    @After
    fun tearDown() {
        runBlocking { store.close() }
        database.close()
        Dispatchers.resetMain()
        sandbox.deleteRecursively()
    }

    @Test
    fun createThroughViewModelPersistsAndClearsDraft() {
        val viewModel = viewModel()
        awaitUntil(viewModel, { viewModel.uiState.value.memoryCount == 0L }, "initial refresh")

        viewModel.onTitleChange("Site access")
        viewModel.onContentChange("Gate code for Site 7 is 4412.")
        viewModel.create()

        awaitUntil(viewModel, { viewModel.uiState.value.memories.size == 1 }, "create did not persist")
        val state = viewModel.uiState.value
        assertEquals("", state.draftTitle)
        assertEquals("", state.draftContent)
        assertEquals(1, state.memories.size)
        assertEquals("Site access", state.memories[0].title)
        assertEquals(1L, state.memoryCount)
        assertNull(state.error)
    }

    @Test
    fun semanticSearchThroughViewModelPopulatesResults() {
        val viewModel = viewModel()
        awaitUntil(viewModel, { viewModel.uiState.value.memoryCount == 0L }, "initial refresh")

        viewModel.onTitleChange("P-101 repair")
        viewModel.onContentChange("P-101 seal failed due to cavitation; the seal was replaced.")
        viewModel.create()
        awaitUntil(viewModel, { viewModel.uiState.value.memories.isNotEmpty() }, "create did not persist")

        viewModel.onQueryChange("p101 seal cavitation")
        viewModel.search()

        awaitUntil(viewModel, { viewModel.uiState.value.searchActive }, "search did not complete")
        val state = viewModel.uiState.value
        assertTrue(state.results.isNotEmpty())
        assertEquals(1, state.results.size)
        assertTrue(state.results.first().score > 0.0)
        assertEquals("P-101 repair", state.results.first().memory.title)
    }

    @Test
    fun deleteThroughViewModelUpdatesStateAndCount() {
        val viewModel = viewModel()
        awaitUntil(viewModel, { viewModel.uiState.value.memoryCount == 0L }, "initial refresh")

        viewModel.onTitleChange("Stale note")
        viewModel.onContentChange("redundant reminder")
        viewModel.create()
        awaitUntil(viewModel, { viewModel.uiState.value.memories.isNotEmpty() }, "create did not persist")

        val id = viewModel.uiState.value.memories[0].memoryId
        viewModel.delete(id)

        awaitUntil(viewModel, { viewModel.uiState.value.memories.isEmpty() }, "delete did not persist")
        assertEquals(0L, viewModel.uiState.value.memoryCount)
    }

    private fun viewModel(): MemoryViewModel {
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
        return MemoryViewModel(
            createMemory = CreateMemoryUseCase(repository),
            listMemories = ListMemoriesUseCase(repository),
            searchMemories = SearchMemoriesUseCase(repository),
            deleteMemory = DeleteMemoryUseCase(repository),
            documentReader = reader,
            ingestDocument = IngestDocumentUseCase(ingestionService),
            policyEngine = com.example.EdgeMemo.data.policy.DefaultPolicyEngine(
                com.example.EdgeMemo.data.policy.DefaultRedactionService(),
            ),
        )
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
        val state = try {
            runBlocking { repository.list() }.size
        } catch (_: Exception) {
            -1
        }
        fail("$message (repository size=$state, uiState=${viewModel.uiState.value})")
    }
}