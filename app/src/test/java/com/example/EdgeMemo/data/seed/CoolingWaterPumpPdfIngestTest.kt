package com.example.EdgeMemo.data.seed

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.document.DocumentChunker
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.data.document.ContentResolverDocumentReader
import com.example.EdgeMemo.data.document.DocumentExtractorRegistry
import com.example.EdgeMemo.data.document.MarkdownDocumentExtractor
import com.example.EdgeMemo.data.document.PdfDocumentExtractor
import com.example.EdgeMemo.data.document.TxtDocumentExtractor
import com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore
import com.example.EdgeMemo.data.local.sync.DefaultQdrantSyncEngine
import com.example.EdgeMemo.data.local.sync.QdrantChangeDetector
import com.example.EdgeMemo.data.local.sync.QdrantSyncOperationStore
import com.example.EdgeMemo.data.policy.DefaultPolicyEngine
import com.example.EdgeMemo.data.policy.DefaultRedactionService
import com.example.EdgeMemo.data.repository.QdrantRecordMemoryRepository
import com.example.EdgeMemo.data.sync.UnimplementedQdrantSyncRemote
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource
import com.example.EdgeMemo.domain.document.DocumentIngestionService
import com.example.EdgeMemo.domain.document.IngestDocumentUseCase
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the bundled fictional P-103 demo PDF is ingested through the REAL
 * document pipeline (extract → chunk → embed → Qdrant Edge) and that its chunks
 * are associated with the isolated `p-103` namespace. No fake DOCUMENT record is
 * asserted — the stored text must be the PDF's actual extracted text. Seeding is
 * verified idempotent (a second run adds nothing).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoolingWaterPumpPdfIngestTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() = TestNativeLoader.ensureLoaded()
    }

    private lateinit var context: Context
    private val dir = File.createTempFile("p103pdf-", "").apply { delete(); mkdirs() }
    private val store = QdrantEdgeRecordStore(File(dir, "qdrant_sync_store"))
    private val embeddings = FeatureHashingEmbeddingService()
    private val operations = QdrantSyncOperationStore(store)
    private val repository = QdrantRecordMemoryRepository(
        recordStore = store,
        syncEngine = DefaultQdrantSyncEngine(
            recordStore = store,
            operationStore = operations,
            detector = QdrantChangeDetector(store, operations),
            remote = UnimplementedQdrantSyncRemote(),
            cloudKnowledge = object : CloudKnowledgeRemoteDataSource {
                override suspend fun pullKnowledge(cursor: String?) =
                    throw EdgeError.CloudUnavailable("offline pdf ingest test")
            },
        ),
        embeddingService = embeddings,
        policyEngine = DefaultPolicyEngine(DefaultRedactionService()),
    )
    private lateinit var reader: ContentResolverDocumentReader
    private lateinit var ingest: IngestDocumentUseCase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        reader = ContentResolverDocumentReader(context)
        val service = DocumentIngestionService(
            reader = reader,
            registry = DocumentExtractorRegistry(
                listOf(PdfDocumentExtractor(context), MarkdownDocumentExtractor(), TxtDocumentExtractor()),
            ),
            chunker = DocumentChunker(),
            repository = repository,
        )
        ingest = IngestDocumentUseCase(service)
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { store.close() } }
        dir.deleteRecursively()
    }

    private suspend fun p103DocChunks() = repository.list().filter {
        it.type == MemoryType.DOCUMENT && it.subjectKey == "${CoolingWaterPumpDataset.NAMESPACE}/document"
    }

    @Test
    fun bundledPdfIngestsRealTextIntoP103Namespace() = runBlocking {
        assertTrue(repository.list().isEmpty())

        CoolingWaterPumpDataset.seedPdfIfNeeded(context, repository, reader, ingest)

        val chunks = p103DocChunks()
        assertTrue("expected at least one P-103 document chunk from the real PDF", chunks.isNotEmpty())
        // Chunks must be associated with the P-103 namespace (isolated from P-101).
        assertTrue(chunks.all { it.subjectKey?.startsWith("p-103/") == true })
        // The stored text is the PDF's ACTUAL extracted content, not fabricated.
        val joined = chunks.joinToString(" ") { it.content }
        assertTrue("PDF title line must be present", joined.contains("Cooling Water Pump P-103"))
        assertTrue("incident reference must come from the PDF text", joined.contains("DEMO-EVT-103-01"))
        // A real document id is stamped in metadata by the pipeline.
        assertTrue(chunks.all { it.metadata[DocumentIngestionService.META_DOCUMENT_ID] != null })
    }

    @Test
    fun pdfIngestIsIdempotent() = runBlocking {
        CoolingWaterPumpDataset.seedPdfIfNeeded(context, repository, reader, ingest)
        val first = p103DocChunks().size
        assertTrue(first > 0)

        // Second call must not duplicate (guarded by the flag + existence check).
        CoolingWaterPumpDataset.seedPdfIfNeeded(context, repository, reader, ingest)
        assertEquals(first, p103DocChunks().size)
    }
}
