package com.example.EdgeMemo.ai.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractiveLLMServiceTest {

    private val service = ExtractiveLLMService()

    private fun evidence(index: Int, text: String) = LlmEvidence(
        index = index,
        title = "m$index",
        source = "src-$index",
        text = text,
        page = 1,
        section = null,
    )

    @Test
    fun answersOnlyWithVerbatimSentencesAndCitations() = runBlocking {
        val request = LlmRequest(
            question = "What happened to the P-101 seal?",
            evidence = listOf(
                evidence(1, "The P-101 seal failed due to cavitation. The seal was replaced during the outage."),
                evidence(2, "The pump alignment was checked afterwards. Everything was within tolerance."),
            ),
        )

        val response = service.answer(request)

        assertTrue(response.answer.isNotBlank())
        assertTrue(response.answer.contains("[1]"))
        assertTrue(response.usedEvidenceIndexes.contains(1))
        for ((sentence, cited) in parseCitations(response.answer)) {
            val ev = request.evidence.first { it.index == cited }
            assertTrue("citation $cited must quote evidence verbatim: '$sentence'", ev.text.contains(sentence))
        }
    }

    /** Parses `sentence [n] ...` segments of the answer into (text, citation) pairs. */
    private fun parseCitations(answer: String): List<Pair<String, Int>> {
        val segments = mutableListOf<Pair<String, Int>>()
        var cursor = 0
        while (cursor < answer.length) {
            val open = answer.indexOf(" [", cursor)
            if (open < 0) break
            val close = answer.indexOf("]", open)
            if (close < 0) break
            val cited = answer.substring(open + 2, close).toIntOrNull()
            val sentence = answer.substring(cursor, open).trim()
            if (cited != null && sentence.isNotEmpty()) segments.add(sentence to cited)
            cursor = close + 1
        }
        return segments
    }

    @Test
    fun emptyEvidenceYieldsEmptyAnswer() = runBlocking {
        val response = service.answer(LlmRequest(question = "anything", evidence = emptyList()))
        assertEquals("", response.answer)
        assertEquals(emptyList<Int>(), response.usedEvidenceIndexes)
    }

    @Test
    fun noOverlapFallsBackToTopEvidenceVerbatim() = runBlocking {
        val request = LlmRequest(
            question = "zebra migrations",
            evidence = listOf(evidence(1, "The pump coupler was replaced on Tuesday after inspection.")),
        )
        val response = service.answer(request)
        assertTrue(response.answer.contains("pump coupler"))
        assertTrue(response.answer.contains("[1]"))
    }

    @Test
    fun respectsMaxSentences() = runBlocking {
        val long = (1..10).joinToString(" ") { "The seal test#${it} passed the pressure check." }
        val request = LlmRequest(
            question = "seal test pressure check",
            evidence = listOf(evidence(1, long)),
            maxSentences = 2,
        )
        val response = service.answer(request)
        val count = response.answer.split("[").size - 1
        assertTrue("expected at most 2 cited sentences but found $count", count <= 2)
    }

    @Test
    fun neverFabricatesSentences() = runBlocking {
        val request = LlmRequest(
            question = "baseline veterinary protocol",
            evidence = listOf(evidence(1, "Torque the flange bolts to 120 Nm in a star pattern.")),
        )
        val response = service.answer(request)
        val body = response.answer.replace(Regex("\\s+\\[\\d+]\\s*"), "").trim()
        assertTrue(request.evidence.first().text.contains(body))
    }
}