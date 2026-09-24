package com.example.EdgeMemo.data.document

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.document.DocumentChunker
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.local.room.MemoryDao
import com.example.EdgeMemo.data.local.room.MemoryEntity
import com.example.EdgeMemo.data.repository.DefaultMemoryRepository
import com.example.EdgeMemo.domain.document.DocumentIngestionService
import com.example.EdgeMemo.domain.document.IngestionStage
import com.example.EdgeMemo.native.qdrant.QdrantEdgeVectorStore
import com.example.EdgeMemo.testing.TestNativeLoader
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DocumentIngestionServiceTest {

    private lateinit var context: Context
    private lateinit var sandbox: File
    private lateinit var dbName: String
    private lateinit var qdrantDir: File
    private lateinit var database: EdgeMindDatabase
    private lateinit var store: QdrantEdgeVectorStore
    private lateinit var repository: DefaultMemoryRepository
    private lateinit var service: DocumentIngestionService

    @Before
    fun setUp() {
        TestNativeLoader.ensureLoaded()
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
        sandbox = File(context.cacheDir, "ingest-${UUID.randomUUID()}").apply { mkdirs() }
        dbName = "ingest-${UUID.randomUUID()}-db"
        qdrantDir = File(sandbox, "qdrant")
        buildRepository()
    }

    @After
    fun tearDown() {
        runBlocking { store.close() }
        database.close()
        sandbox.deleteRecursively()
    }

    private fun buildRepository(daoOverride: ((MemoryDao) -> MemoryDao)? = null) {
        database = Room.databaseBuilder(context, EdgeMindDatabase::class.java, dbName).build()
        store = QdrantEdgeVectorStore(qdrantDir)
        val dao = database.memoryDao()
        repository = DefaultMemoryRepository(
            daoOverride?.invoke(dao) ?: dao,
            store,
            FeatureHashingEmbeddingService(),
        )
        service = DocumentIngestionService(
            reader = ContentResolverDocumentReader(context),
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
    }

    private fun writeFile(name: String, content: String): Uri {
        val file = File(sandbox, name)
        file.writeText(content)
        return Uri.fromFile(file)
    }

    private fun writePdf(name: String, pages: List<String>): Uri {
        val document = PDDocument()
        pages.forEach { text ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 12f)
                stream.newLineAtOffset(50f, 700f)
                stream.showText(text)
                stream.endText()
            }
        }
        val bytes = ByteArrayOutputStream().use { out ->
            document.save(out)
            document.close()
            out.toByteArray()
        }
        val file = File(sandbox, name)
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private suspend fun ingest(uri: Uri): com.example.EdgeMemo.domain.document.IngestionResult {
        val source = ContentResolverDocumentReader(context).resolve(uri)
        return service.ingest(source)
    }

    @Test
    fun ingestTxtCreatesSearchableDocumentChunks() = runBlocking {
        val uri = writeFile(
            "pump-notes.txt",
            "The P-101 pump seal failed because of cavitation. The seal was replaced during the outage.",
        )

        val result = ingest(uri)

        assertTrue(result.chunkCount >= 1)
        assertEquals(result.chunkCount, result.memories.size)

        val stored = repository.list()
        assertEquals(result.chunkCount, stored.size)
        assertTrue(stored.all { it.type == MemoryType.DOCUMENT })
        assertTrue(stored.all { it.source == "pump-notes.txt" })
        val expectedChunkIds = (0 until result.chunkCount).map { "${result.documentId}#$it" }.toSet()
        assertEquals(expectedChunkIds, stored.mapNotNull { it.chunkId }.toSet())
        assertTrue(stored.all { it.metadata[DocumentIngestionService.META_DOCUMENT_ID] == result.documentId })
        assertEquals("txt", stored.first().metadata[DocumentIngestionService.META_FORMAT])

        val hits = repository.search("P-101 seal cavitation", limit = 5)
        assertTrue("expected semantic hit for ingested document", hits.isNotEmpty())
        assertTrue(hits.any { it.memory.type == MemoryType.DOCUMENT })
    }

    @Test
    fun ingestMarkdownPreservesSectionMetadata() = runBlocking {
        val uri = writeFile(
            "manual.md",
            "# Manual\n\n## Repairs\n\nReplace the P-101 seal.\n\n## Safety\n\nIsolate the pump.",
        )

        val result = ingest(uri)
        val stored = repository.list()

        assertEquals(result.chunkCount, stored.size)
        val repairs = stored.first { it.content.contains("P-101") }
        assertEquals("Manual > Repairs", repairs.metadata[DocumentIngestionService.META_SECTION])
        assertEquals("Manual", repairs.metadata[DocumentIngestionService.META_DOCUMENT_TITLE])
    }

    @Test
    fun ingestPdfPreservesPageMetadata() = runBlocking {
        val uri = writePdf(
            "report.pdf",
            listOf("Page one covers the P-101 inspection.", "Page two covers the SKF-6205 bearing."),
        )

        val result = ingest(uri)
        val stored = repository.list().sortedBy { it.metadata[DocumentIngestionService.META_CHUNK_INDEX]?.toInt() }

        assertTrue(stored.any { it.metadata[DocumentIngestionService.META_PAGE] == "1" })
        assertTrue(stored.any { it.metadata[DocumentIngestionService.META_PAGE] == "2" })
        assertTrue(stored.all { it.metadata[DocumentIngestionService.META_FORMAT] == "pdf" })
    }

    @Test
    fun ingestReportsStagesInOrder() = runBlocking {
        val uri = writeFile("stages.txt", "A short note about alignment tolerances.")
        val source = ContentResolverDocumentReader(context).resolve(uri)
        val stages = ArrayList<IngestionStage>()

        service.ingest(source) { stages.add(it) }

        assertEquals(
            listOf(
                IngestionStage.EXTRACTING,
                IngestionStage.CHUNKING,
                IngestionStage.EMBEDDING,
                IngestionStage.STORING,
                IngestionStage.COMPLETED,
            ),
            stages,
        )
    }

    @Test
    fun unsupportedDocumentTypeIsRejectedWithoutWrites() {
        val uri = writeFile("legacy.docx", "binary-ish content")
        try {
            runBlocking { ingest(uri) }
            fail("expected InvalidDocument")
        } catch (e: EdgeError.InvalidDocument) {
            assertTrue(e.message!!.contains("unsupported"))
        }
        assertEquals(0L, runBlocking { repository.count() })
    }

    @Test
    fun emptyDocumentIsRejected() {
        val uri = writeFile("empty.txt", "   \n\n   ")
        try {
            runBlocking { ingest(uri) }
            fail("expected InvalidDocument")
        } catch (e: EdgeError.InvalidDocument) {
            assertTrue(e.message!!.contains("empty"))
        }
        assertEquals(0L, runBlocking { repository.count() })
    }

    @Test
    fun batchWriteRollsBackVectorsWhenMetadataInsertFails() = runBlocking {
        buildRepository { dao ->
            object : MemoryDao by dao {
                override suspend fun insertAll(entities: List<MemoryEntity>) {
                    throw RuntimeException("simulated metadata failure")
                }
            }
        }
        val longText = (1..400).joinToString(" ") { "detail$it about the pump overhaul procedure." }
        val uri = writeFile("long.txt", longText)

        try {
            ingest(uri)
            fail("expected LocalStorageError")
        } catch (e: EdgeError.LocalStorageError) {
            // expected: the whole batch is rejected
        }

        assertEquals(0L, repository.count())
        val hits = repository.search("pump overhaul procedure", limit = 5)
        assertTrue("rolled-back vectors must not be searchable", hits.isEmpty())
    }

    @Test
    fun ingestedDocumentSurvivesRestart() = runBlocking {
        val uri = writeFile("persistent.txt", "The E-4417 encoder requires recalibration after service.")
        val result = ingest(uri)
        assertTrue(result.chunkCount >= 1)

        store.close()
        database.close()
        buildRepository()

        val hits = repository.search("E-4417 encoder recalibration", limit = 5)
        assertTrue("document should be retrievable after restart", hits.isNotEmpty())
        assertNotNull(hits.first { it.memory.type == MemoryType.DOCUMENT })
    }
}
