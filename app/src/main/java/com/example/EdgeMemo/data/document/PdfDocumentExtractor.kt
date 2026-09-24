package com.example.EdgeMemo.data.document

import android.content.Context
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.document.DocumentBlock
import com.example.EdgeMemo.core.document.DocumentSource
import com.example.EdgeMemo.core.document.ExtractedDocument
import com.example.EdgeMemo.core.document.TextNormalizer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts real text from PDFs with PDFBox-Android. Pages are emitted as
 * separate blocks so chunks keep page numbers. Image-only/scanned PDFs have no
 * extractable text and are reported explicitly (OCR is not implemented).
 */
class PdfDocumentExtractor(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DocumentExtractor {

    private val appContext: Context = context.applicationContext

    override val supportedMimeTypes: Set<String> = setOf("application/pdf")
    override val supportedExtensions: Set<String> = setOf("pdf")

    override suspend fun extract(input: InputStream, source: DocumentSource): ExtractedDocument =
        withContext(dispatcher) {
            ensureInitialized()
            val bytes = input.readBytes()
            val document = try {
                PDDocument.load(ByteArrayInputStream(bytes))
            } catch (e: Exception) {
                throw EdgeError.InvalidDocument("could not parse PDF: ${source.displayName}", e)
            }
            try {
                val pageCount = document.numberOfPages
                if (pageCount == 0) {
                    throw EdgeError.InvalidDocument("PDF has no pages: ${source.displayName}")
                }
                val stripper = PDFTextStripper()
                val blocks = ArrayList<DocumentBlock>()
                for (page in 1..pageCount) {
                    stripper.startPage = page
                    stripper.endPage = page
                    val text = TextNormalizer.normalize(stripper.getText(document))
                    if (text.isNotBlank()) {
                        blocks.add(DocumentBlock(text = text, page = page))
                    }
                }
                if (blocks.isEmpty()) {
                    throw EdgeError.InvalidDocument(
                        "PDF appears to be image-only or scanned; no extractable text " +
                            "(OCR is not implemented): ${source.displayName}",
                    )
                }
                ExtractedDocument(
                    title = source.title,
                    blocks = blocks,
                    metadata = mapOf(
                        "format" to "pdf",
                        "pages" to pageCount.toString(),
                    ),
                )
            } finally {
                document.close()
            }
        }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(lock) {
            if (!initialized) {
                PDFBoxResourceLoader.init(appContext)
                initialized = true
            }
        }
    }

    private companion object {
        val lock = Any()

        @Volatile
        var initialized = false
    }
}
