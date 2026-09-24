package com.example.EdgeMemo.data.document

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.document.DocumentSource
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfDocumentExtractorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val extractor = PdfDocumentExtractor(context)

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
    }

    private fun source(name: String) =
        DocumentSource(uri = "content://docs/$name", displayName = name, mimeType = "application/pdf")

    private fun pdfBytes(pageTexts: List<String>): ByteArray {
        val document = PDDocument()
        pageTexts.forEach { text ->
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
        return ByteArrayOutputStream().use { out ->
            document.save(out)
            document.close()
            out.toByteArray()
        }
    }

    @Test
    fun extractsTextPerPage() = runBlocking {
        val bytes = pdfBytes(
            listOf("P-101 seal failure due to cavitation.", "Replace the SKF-6205 bearing."),
        )
        val extracted = extractor.extract(ByteArrayInputStream(bytes), source("repair.pdf"))

        assertEquals("pdf", extracted.metadata["format"])
        assertEquals("2", extracted.metadata["pages"])
        assertEquals(2, extracted.blocks.size)
        assertEquals(1, extracted.blocks[0].page)
        assertEquals(2, extracted.blocks[1].page)
        assertTrue(extracted.blocks[0].text.contains("P-101"))
        assertTrue(extracted.blocks[1].text.contains("SKF-6205"))
    }

    @Test
    fun imageOnlyPdfWithoutTextIsRejected() {
        val bytes = pdfBytes(listOf(""))
        try {
            runBlocking { extractor.extract(ByteArrayInputStream(bytes), source("scan.pdf")) }
            throw AssertionError("expected InvalidDocument")
        } catch (e: EdgeError.InvalidDocument) {
            assertTrue(e.message!!.contains("image-only"))
        }
    }
}
