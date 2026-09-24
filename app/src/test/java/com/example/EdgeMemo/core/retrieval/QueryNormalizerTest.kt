package com.example.EdgeMemo.core.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryNormalizerTest {

    @Test
    fun normalizeCollapsesWhitespaceAndLowercases() {
        assertEquals("p-101 seal failure", QueryNormalizer.normalize("  P-101   SEAL\n failure  "))
    }

    @Test
    fun tokensAreDistinctAndLowerCase() {
        assertEquals(listOf("p-101", "seal", "oil"), QueryNormalizer.tokens(QueryNormalizer.normalize("P-101 P-101 seal and OIL")))
    }

    @Test
    fun stopwordsAreRemovedFromTokens() {
        assertEquals(
            listOf("planetary", "gearbox", "torque", "specification"),
            QueryNormalizer.tokens(QueryNormalizer.normalize("What is the planetary gearbox torque specification?")),
        )
    }

    @Test
    fun identifierDetectionMatchesTechnicalCodes() {
        assertTrue(QueryNormalizer.isIdentifier("p-101"))
        assertTrue(QueryNormalizer.isIdentifier("skf-6205"))
        assertTrue(QueryNormalizer.isIdentifier("e-4417"))
        assertTrue(QueryNormalizer.isIdentifier("p101"))
        assertFalse(QueryNormalizer.isIdentifier("seal"))
        assertFalse(QueryNormalizer.isIdentifier("to"))
        assertFalse(QueryNormalizer.isIdentifier("2024"))
    }
}