package com.example.EdgeMemo.data.remote

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.data.cloud.HttpCloudAnswerDataSource
import com.example.EdgeMemo.testing.LocalHttpBackend
import com.example.EdgeMemo.testing.readBody
import com.example.EdgeMemo.testing.respond
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real HTTP cloud-answer remote against a deterministic in-test backend.
 * Verifies provenance parsing and that failures surface as CloudUnavailable
 * (EscalatingRagService → honest Unavailable limitation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HttpCloudAnswerDataSourceTest {

    @Test
    fun parsesAnswerWithCloudProvenance() = runBlocking {
        val backend = LocalHttpBackend.start { exchange ->
            exchange.respond(
                200,
                """{"answer":"The latest revision is 4.","authority":"cloud-llm · model-x","provider":"model-x","requestId":"req-7"}""",
            )
        }
        try {
            val remote = HttpCloudAnswerDataSource(backend.baseUrl)
            val answer = remote.ask("What is the latest revision?")

            assertEquals("The latest revision is 4.", answer.answer)
            assertEquals("cloud-llm · model-x", answer.authority)
            assertEquals("model-x", answer.metadata["provider"])
            assertEquals("req-7", answer.metadata["requestId"])
        } finally {
            backend.close()
        }
    }

    @Test
    fun onlyTheQuestionIsSentToTheBackend() = runBlocking {
        var capturedBody: String? = null
        val backend = LocalHttpBackend.start { exchange ->
            capturedBody = exchange.readBody()
            exchange.respond(200, """{"answer":"ok","authority":"cloud-llm · m"}""")
        }
        try {
            val remote = HttpCloudAnswerDataSource(backend.baseUrl)
            remote.ask("What torque for P-101?")
        } finally {
            backend.close()
        }
        val body = capturedBody.orEmpty()
        assertTrue(body.contains("\"question\":\"What torque for P-101?\""))
        // The privacy contract: no evidence/context keys may exist.
        assertTrue(!body.contains("evidence"))
        assertTrue(!body.contains("memory"))
    }

    @Test
    fun blankBackendAnswerSurfacesAsCloudUnavailable() = runBlocking {
        val backend = LocalHttpBackend.start { exchange ->
            exchange.respond(200, """{"answer":"   ","authority":"cloud-llm · m"}""")
        }
        try {
            val remote = HttpCloudAnswerDataSource(backend.baseUrl)
            val result = runCatching { remote.ask("q") }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is EdgeError.CloudUnavailable)
        } finally {
            backend.close()
        }
    }

    @Test
    fun backendFailureSurfacesAsCloudUnavailable() = runBlocking {
        val backend = LocalHttpBackend.start { exchange ->
            exchange.respond(503, """{"error":{"code":"LLM_NOT_CONFIGURED","message":"none"}}""")
        }
        try {
            val remote = HttpCloudAnswerDataSource(backend.baseUrl)
            val result = runCatching { remote.ask("q") }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is EdgeError.CloudUnavailable)
        } finally {
            backend.close()
        }
    }

    @Test
    fun unreachableBackendSurfacesAsCloudUnavailable() = runBlocking {
        val remote = HttpCloudAnswerDataSource("http://127.0.0.1:1")
        val result = runCatching { remote.ask("q") }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is EdgeError.CloudUnavailable)
    }
}
