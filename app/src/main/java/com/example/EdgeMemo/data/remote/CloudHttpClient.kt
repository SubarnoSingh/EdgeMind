package com.example.EdgeMemo.data.remote

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Minimal Android HTTP client for the EdgeMind backend. Zero new dependencies
 * (HttpURLConnection), runs on IO, and maps every non-2xx response to an
 * [HttpFailure] carrying only the status code and the backend's safe error
 * payload — never request content.
 *
 * The backend URL is configuration, not a secret; production deployments must
 * use HTTPS (the app's network security config already forbids cleartext
 * except for local development hosts).
 */
class CloudHttpClient(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 15_000,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    class HttpFailure(
        val statusCode: Int,
        val code: String?,
        val detail: String?,
    ) : IOException("backend responded $statusCode: ${detail ?: code ?: "no detail"}")

    suspend fun putJson(
        path: String,
        body: String,
        readTimeoutMs: Int = this.readTimeoutMs,
    ): String = request("PUT", path, body, readTimeoutMs)

    suspend fun postJson(
        path: String,
        body: String,
        readTimeoutMs: Int = this.readTimeoutMs,
    ): String = request("POST", path, body, readTimeoutMs)

    suspend fun getJson(path: String, readTimeoutMs: Int = this.readTimeoutMs): String =
        request("GET", path, null, readTimeoutMs)

    private suspend fun request(
        method: String,
        path: String,
        body: String?,
        readTimeoutMs: Int,
    ): String = withContext(dispatcher) {
        val url = URL(baseUrl.trimEnd('/') + path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val error = parseError(text)
                throw HttpFailure(status, error.first, error.second)
            }
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun parseError(text: String): Pair<String?, String?> {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null to trimmed.take(200).ifEmpty { null }
        return try {
            val json = org.json.JSONObject(trimmed)
            val error = json.optJSONObject("error")
            (error?.optString("code")?.takeIf { it.isNotBlank() }) to
                (error?.optString("message")?.takeIf { it.isNotBlank() })
        } catch (_: Exception) {
            null to trimmed.take(200).ifEmpty { null }
        }
    }
}
