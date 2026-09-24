package com.example.EdgeMemo.data.document

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.document.DocumentSource
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtDocumentExtractorTest {

    private val extractor = TxtDocumentExtractor()
    private fun source(name: String, mime: String? = "text/plain") =
        DocumentSource(uri = "content://docs/$name", displayName = name, mimeType = mime)

    @Test
    fun extractsParagraphsAndDerivesTitleFromFileName() = runBlocking {
        val text = "First paragraph about P-101.\r\n\r\nSecond paragraph about the seal."
        val extracted = extractor.extract(ByteArrayInputStream(text.toByteArray()), source("pump-notes.txt"))

        assertEquals("pump-notes", extracted.title)
        assertEquals(2, extracted.blocks.size)
        assertEquals("First paragraph about P-101.", extracted.blocks[0].text)
        assertEquals("Second paragraph about the seal.", extracted.blocks[1].text)
        assertEquals("txt", extracted.metadata["format"])
    }

    @Test
    fun blankDocumentIsRejected() {
        try {
            runBlocking {
                extractor.extract(ByteArrayInputStream("   \n\n  ".toByteArray()), source("empty.txt"))
            }
            throw AssertionError("expected InvalidDocument")
        } catch (e: EdgeError.InvalidDocument) {
            assertTrue(e.message!!.contains("empty"))
        }
    }

    @Test
    fun supportsPlainTextByExtensionAndMime() {
        assertTrue(extractor.supports(source("notes.txt")))
        assertTrue(extractor.supports(source("notes.unknown", mime = "text/plain")))
        assertFalse(extractor.supports(source("report.pdf", mime = "application/pdf")))
    }
}
