package com.example.EdgeMemo.core.document

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun normalizeLineBreaksHandlesCrLfAndCr() {
        assertEquals("a\nb\nc", TextNormalizer.normalizeLineBreaks("a\r\nb\rc"))
    }

    @Test
    fun normalizeCollapsesWhitespaceAndControlCharacters() {
        val raw = "  Hello\t\tworld \u0000\u0007 \n\n\n  second   line  "
        assertEquals("Hello world\n\nsecond line", TextNormalizer.normalize(raw))
    }

    @Test
    fun normalizeRemovesByteOrderMark() {
        assertEquals("Title", TextNormalizer.normalize("\uFEFFTitle"))
    }

    @Test
    fun normalizeInlineProducesSingleLine() {
        assertEquals("a b c", TextNormalizer.normalizeInline("a\n  b\n\tc"))
    }
}
