package com.example.EdgeMemo.data.cloud

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.data.remote.CloudHttpClient
import com.example.EdgeMemo.domain.cloud.CloudAnswer
import com.example.EdgeMemo.domain.cloud.CloudAnswerDataSource
import org.json.JSONObject

/**
 * Real Phase-8 cloud answer remote: Android → EdgeMind backend → cloud LLM.
 * The ONLY context sent is the question text (the Phase 8 privacy contract);
 * the backend owns the LLM credentials. A blank answer or any backend failure
 * surfaces as `CloudUnavailable` so EscalatingRagService reports an honest
 * limitation — never a fabricated answer.
 */
class HttpCloudAnswerDataSource(
    private val baseUrl: String,
    private val client: CloudHttpClient = CloudHttpClient(baseUrl),
) : CloudAnswerDataSource {

    override suspend fun ask(question: String): CloudAnswer {
        if (baseUrl.isBlank()) {
            throw EdgeError.CloudUnavailable("no cloud answer backend is configured")
        }
        if (question.isBlank()) {
            throw EdgeError.CloudUnavailable("question is empty")
        }
        val body = JSONObject().put("question", question).toString()
        val text = try {
            client.postJson("/answers", body, readTimeoutMs = ANSWER_READ_TIMEOUT_MS)
        } catch (e: CloudHttpClient.HttpFailure) {
            throw EdgeError.CloudUnavailable("cloud answer backend unavailable (${e.statusCode})")
        } catch (e: Exception) {
            throw EdgeError.CloudUnavailable("cloud answer backend unreachable")
        }
        return parse(question, text)
    }

    private fun parse(question: String, text: String): CloudAnswer {
        return try {
            val json = JSONObject(text)
            val answer = json.optString("answer").trim()
            if (answer.isEmpty()) {
                throw EdgeError.CloudUnavailable("cloud answer backend returned no answer")
            }
            CloudAnswer(
                question = question,
                answer = answer,
                authority = json.optString("authority").takeIf { it.isNotBlank() },
                metadata = buildMap {
                    json.optString("provider").takeIf { it.isNotBlank() }?.let { put("provider", it) }
                    json.optString("requestId").takeIf { it.isNotBlank() }?.let { put("requestId", it) }
                },
            )
        } catch (e: EdgeError) {
            throw e
        } catch (e: Exception) {
            throw EdgeError.CloudUnavailable("cloud answer backend returned an invalid response")
        }
    }

    companion object {
        private const val ANSWER_READ_TIMEOUT_MS = 25_000
    }
}
