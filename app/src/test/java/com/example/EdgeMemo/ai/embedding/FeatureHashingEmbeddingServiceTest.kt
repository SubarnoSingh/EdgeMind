package com.example.EdgeMemo.ai.embedding

import com.example.EdgeMemo.core.common.EdgeError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FeatureHashingEmbeddingServiceTest {

    private val service = FeatureHashingEmbeddingService()

    @Test
    fun `same text produces identical vector across instances`() = runBlocking {
        val first = FeatureHashingEmbeddingService().embed("P-101 seal replacement")
        val second = FeatureHashingEmbeddingService().embed("P-101 seal replacement")
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun `vector has declared dimension and unit norm`() = runBlocking {
        val vector = service.embed("pump maintenance procedure revision 4")
        assertEquals(512, vector.size)
        val norm = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() })
        assertEquals(1.0, norm, 1e-5)
    }

    @Test
    fun `different texts produce different vectors`() = runBlocking {
        val a = service.embed("seal failed due to cavitation")
        val b = service.embed("gate code for site seven is 4412")
        assertFalse(a.contentEquals(b))
        assertNotEquals(0.0f, a.max())
    }

    @Test
    fun `related texts are closer than unrelated texts`() = runBlocking {
        val memory = service.embed("P-101 pump seal replacement caused by cavitation")
        val related = service.embed("seal replacement on pump P-101 due to cavitation")
        val unrelated = service.embed("warehouse badge renewal scheduled for next month")
        val relatedScore = cosine(memory, related)
        val unrelatedScore = cosine(memory, unrelated)
        assertTrue(
            "related=$relatedScore should beat unrelated=$unrelatedScore",
            relatedScore > unrelatedScore,
        )
        assertTrue(relatedScore > 0.5)
    }

    @Test
    fun `exact identifier overlap ranks above unrelated text`() = runBlocking {
        val memory = service.embed("bearing replacement SKF-6205 reinstalled")
        val withId = service.embed("SKF-6205 bearing")
        val unrelated = service.embed("lunch break schedule")
        assertTrue(cosine(memory, withId) > cosine(memory, unrelated))
    }

    @Test
    fun `blank input is rejected with typed error`() {
        try {
            runBlocking { service.embed("   ") }
            fail("expected InvalidInput")
        } catch (expected: EdgeError.InvalidInput) {
            // expected
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        return (dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))).toFloat()
    }
}