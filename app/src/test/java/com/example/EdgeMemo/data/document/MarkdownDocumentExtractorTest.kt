package com.example.EdgeMemo.data.document

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.document.DocumentSource
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDocumentExtractorTest {

    private val extractor = MarkdownDocumentExtractor()
    private fun source(name: String, mime: String? = "text/markdown") =
        DocumentSource(uri = "content://docs/$name", displayName = name, mimeType = mime)

    @Test
    fun extractsHeadingSectionsAndInlineText() = runBlocking {
        val markdown = """
            # Maintenance Manual

            ## Repairs

            Replace the **P-101** seal. See `SKF-6205` for the bearing.

            - Check alignment
            - Torque bolts

            ## Safety

            Isolate the pump first.
        """.trimIndent()

        val extracted = extractor.extract(ByteArrayInputStream(markdown.toByteArray()), source("manual.md"))

        assertEquals("Maintenance Manual", extracted.title)
        assertEquals("markdown", extracted.metadata["format"])

        val repairs = extracted.blocks.first { it.text.contains("Replace the P-101 seal") }
        assertEquals("Maintenance Manual > Repairs", repairs.section)

        val listItem = extracted.blocks.first { it.text == "Check alignment" }
        assertEquals("Maintenance Manual > Repairs", listItem.section)

        val safety = extracted.blocks.first { it.text == "Isolate the pump first." }
        assertEquals("Maintenance Manual > Safety", safety.section)

        assertTrue(extracted.blocks.any { it.text.contains("SKF-6205") })
    }

    @Test
    fun titleFallsBackToFileNameWithoutHeading() = runBlocking {
        val extracted = extractor.extract(
            ByteArrayInputStream("Just a paragraph.".toByteArray()),
            source("notes.md"),
        )
        assertEquals("notes", extracted.title)
    }

    @Test
    fun emptyMarkdownIsRejected() {
        try {
            runBlocking {
                extractor.extract(ByteArrayInputStream("   \n\n".toByteArray()), source("empty.md"))
            }
            throw AssertionError("expected InvalidDocument")
        } catch (e: EdgeError.InvalidDocument) {
            assertTrue(e.message!!.contains("no text"))
        }
    }
}
