package com.example.EdgeMemo.data.policy

import com.example.EdgeMemo.core.policy.RedactedRepresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRedactionServiceTest {

    private val service = DefaultRedactionService()

    @Test
    fun redactionIsDeterministic() {
        val title = "P-101 repair log"
        val content = "Technician John replaced the P-101 seal. Root cause was cavitation."
        val first = service.redact(title, content)
        val second = service.redact(title, content)
        assertEquals(first, second)
        assertEquals(first.removedTokens, second.removedTokens)
    }

    @Test
    fun removesRoleNamesWithoutRemovingRoleWord() {
        val result = service.redact("Team contact", "Technician John installed the bracket.")
        assertEquals("Technician [redacted] installed the bracket.", result.content)
    }

    @Test
    fun removesEmails() {
        val result = service.redact("Routing", "Send the report to dev@site7.example by Friday.")
        assertFalse(result.content.contains("dev@site7.example"))
        assertTrue(result.content.contains("Friday"))
        assertTrue(result.removedTokens.contains("dev@site7.example"))
    }

    @Test
    fun removesStandaloneDigitRuns() {
        val result = service.redact("Access", "Dial 4412 to open the panel door.")
        assertFalse(result.content.contains("4412"))
        assertTrue(result.removedTokens.contains("4412"))
    }

    @Test
    fun removesTechnicalIdentifiers() {
        val result = service.redact("P-101", "Replaced P-101 seal and fitted SKF-6205 bearing; torque 55 Nm.")
        assertFalse(result.content.contains("P-101"))
        assertFalse(result.content.contains("SKF-6205"))
        assertTrue(result.removedTokens.contains("P-101"))
        assertTrue(result.removedTokens.contains("SKF-6205"))
        // useful prose and un-identifiable values survive
        assertTrue(result.content.contains("seal"))
        assertTrue(result.content.contains("torque"))
        assertTrue(result.content.contains("Nm"))
    }

    @Test
    fun preservesOrdinaryWords() {
        val result = service.redact("Note", "Cavitation caused the seal failure; check the pump weekly.")
        assertEquals("Cavitation caused the seal failure; check the pump weekly.", result.content)
        assertTrue(result.removedTokens.isEmpty())
    }

    @Test
    fun originalInputIsNeverMutated() {
        val title = "P-101 repair log"
        val content = "Technician John replaced the P-101 seal."
        service.redact(title, content)
        assertEquals("P-101 repair log", title)
        assertEquals("Technician John replaced the P-101 seal.", content)
    }

    @Test
    fun removedTokensAreDeduplicated() {
        val result = service.redact("P-101", "P-101 failed, then P-101 was refitted and re-tested.")
        assertEquals(listOf("P-101"), result.removedTokens)
        assertTrue(result.content.doesNotContain("P-101"))
    }

    private fun String.doesNotContain(needle: String): Boolean = !contains(needle)
}