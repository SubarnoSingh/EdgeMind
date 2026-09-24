package com.example.EdgeMemo.core.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentChunkerTest {

    @Test
    fun blankAndEmptyBlocksProduceNoChunks() {
        val chunker = DocumentChunker()
        assertEquals(emptyList<DocumentChunk>(), chunker.chunk(emptyList()))
        assertEquals(emptyList<DocumentChunk>(), chunker.chunk(listOf(DocumentBlock("   \n\n  "))))
    }

    @Test
    fun shortBlockBecomesSingleChunkPreservingPageAndSection() {
        val chunker = DocumentChunker()
        val chunks = chunker.chunk(
            listOf(DocumentBlock("P-101 seal failed.", page = 3, section = "Repairs > Pumps")),
        )
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].index)
        assertEquals("P-101 seal failed.", chunks[0].text)
        assertEquals(3, chunks[0].page)
        assertEquals("Repairs > Pumps", chunks[0].section)
    }

    @Test
    fun longTextIsSplitIntoBoundedChunksWithSequentialIndexes() {
        val chunker = DocumentChunker(targetChars = 80, maxChars = 120, overlapChars = 10)
        val text = (1..300).joinToString(" ") { "word$it" }
        val chunks = chunker.chunk(listOf(DocumentBlock(text)))

        assertTrue("expected multiple chunks", chunks.size > 2)
        assertEquals(chunks.indices.toList(), chunks.map { it.index })
        assertTrue(chunks.all { it.text.length <= 120 })
        assertTrue(chunks.all { it.text.isNotBlank() })
    }

    @Test
    fun consecutiveChunksShareOverlap() {
        val chunker = DocumentChunker(targetChars = 60, maxChars = 200, overlapChars = 20)
        val text = "Alpha bravo charlie delta echo. Foxtrot golf hotel india juliet. " +
            "Kilo lima mike november oscar. Papa quebec romeo sierra tango."
        val chunks = chunker.chunk(listOf(DocumentBlock(text)))

        assertTrue("expected multiple chunks", chunks.size >= 2)
        val seed = chunks[1].text.substringBefore(' ')
        assertTrue("first word of next chunk should come from overlap", chunks[0].text.contains(seed))
    }

    @Test
    fun chunkingIsDeterministic() {
        val chunker = DocumentChunker()
        val blocks = listOf(
            DocumentBlock("First paragraph about SKF-6205 bearing wear. It was replaced.", page = 1),
            DocumentBlock("Second paragraph with more detail about alignment.", page = 2),
        )
        assertEquals(chunker.chunk(blocks), chunker.chunk(blocks))
    }
}
