package com.example.EdgeMemo.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Presentation-only math symbol normalization and time-based greeting logic.
 * Stored memory text and the RAG/LLM pipeline are never touched; these tests
 * pin the rendering contract.
 */
class MarkdownMathTest {

    @Test
    fun greekAndDiracNotationAreRenderedAsUnicode() {
        val raw = "[ |\\psi\\rangle = \\alpha|0\\rangle + \\beta|1\\rangle ]"
        val rendered = replaceMathCommands(raw)
        assertEquals("[ |\u03C8\u27E9 = \u03B1|0\u27E9 + \u03B2|1\u27E9 ]", rendered)
        assertTrue("no raw LaTeX commands may survive", !rendered.contains("\\"))
    }

    @Test
    fun engineeringSymbolsAreReplaced() {
        val raw = "45 Nm \\pm 2 \\cdot \\approx \\rightarrow OK"
        val rendered = replaceMathCommands(raw)
        assertEquals("45 Nm \u00B1 2 \u00B7 \u2248 \u2192 OK", rendered)
    }

    @Test
    fun mathDelimitersAreStrippedButCurrencyDollarIsKept() {
        val raw = "torque is \\(\\tau\\) and \\(E = mc^2\\) and it costs \$5 today"
        val rendered = replaceMathCommands(raw)
        assertTrue(!rendered.contains("\\("))
        assertTrue(!rendered.contains("\\)"))
        assertTrue(rendered.contains("\u03C4"))
        assertTrue(rendered.contains("E = mc^2"))
        assertTrue("single dollar signs may be currency and must survive", rendered.contains("\$5"))
    }

    @Test
    fun plainTextWithBackslashesIsOnlyTouchedForKnownCommands() {
        val raw = "Windows path C:\\Users\\name and sentence \\notacommand word"
        val rendered = replaceMathCommands(raw)
        assertEquals("Windows path C:\\Users\\name and sentence \\notacommand word", rendered)
    }

    @Test
    fun sigmaSumAndSupersetAreReplaced() {
        val raw = "\\Sigma_{i} \\subset \\sum \\geq \\leq \\neq"
        val rendered = replaceMathCommands(raw)
        assertEquals("\u03A3_{i} \u2282 \u2211 \u2265 \u2264 \u2260", rendered)
    }

    @Test
    fun greetingIsTimeBasedAndPersonalizedOnlyWithAName() {
        assertEquals("Good morning", greetingFor(7, ""))
        assertEquals("Good morning", greetingFor(7, "   "))
        assertEquals("Good morning, Subarno", greetingFor(7, "Subarno"))
        assertEquals("Good afternoon", greetingFor(13, ""))
        assertEquals("Good evening, A", greetingFor(19, " A "))
        assertEquals("Good night", greetingFor(23, ""))
        assertEquals("Good night, Subarno", greetingFor(2, "Subarno"))
    }
}
