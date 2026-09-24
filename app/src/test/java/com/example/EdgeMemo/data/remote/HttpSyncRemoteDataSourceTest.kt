package com.example.EdgeMemo.data.remote

import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.sync.OutboxOperationType
import com.example.EdgeMemo.core.sync.SyncFailureKind
import com.example.EdgeMemo.core.sync.SyncOperation
import com.example.EdgeMemo.core.sync.SyncPushResult
import com.example.EdgeMemo.data.sync.HttpSyncRemoteDataSource
import com.example.EdgeMemo.testing.LocalHttpBackend
import com.example.EdgeMemo.testing.readBody
import com.example.EdgeMemo.testing.requestPath
import com.example.EdgeMemo.testing.respond
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real HTTP sync remote against a deterministic in-test backend. Verifies the
 * status→SyncFailureKind mapping the engine depends on and that the request
 * body carries the full evolving-memory schema (enriched by the engine).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HttpSyncRemoteDataSourceTest {

    private fun operation(overrides: SyncOperation.() -> SyncOperation = { this }): SyncOperation =
        SyncOperation(
            operationId = "UPSERT-11111111-1111-4111-8111-111111111111",
            memoryId = "11111111-1111-4111-8111-111111111111",
            operationType = OutboxOperationType.UPSERT,
            title = "Pump procedure",
            content = "Pump maintenance procedure revision 4.",
            syncDecision = SyncDecision.SYNC,
            origin = MemoryOrigin.LOCAL,
            redacted = false,
            version = 3,
            contentHash = "hash-abc",
            subjectKey = "P-101-PROCEDURE",
            type = MemoryType.PROCEDURE,
            tags = listOf("pump"),
            chunkId = null,
            source = "USER_ENTRY",
            supersedes = null,
            tombstone = false,
            updatedAt = 1700000000000L,
            metadata = mapOf("scope" to "site"),
        ).overrides()

    @Test
    fun ackOn2xxReturnsSuccess() = runBlocking {
        val backend = LocalHttpBackend.start { exchange ->
            exchange.respond(201, """{"accepted":true,"operationId":"x","memoryId":"y"}""")
        }
        try {
            val remote = HttpSyncRemoteDataSource(backend.baseUrl)
            assertEquals(SyncPushResult.Success, remote.push(operation()))
        } finally {
            backend.close()
        }
    }

    @Test
    fun badRequestsMapToPermanentRejected() = runBlocking {
        for (status in listOf(400, 422)) {
            val backend = LocalHttpBackend.start { exchange ->
                exchange.respond(status, """{"error":{"code":"INVALID_REQUEST","message":"bad"}}""")
            }
            try {
                val remote = HttpSyncRemoteDataSource(backend.baseUrl)
                assertEquals(
                    SyncPushResult.Failure(SyncFailureKind.REJECTED),
                    remote.push(operation()),
                )
            } finally {
                backend.close()
            }
        }
    }

    @Test
    fun authFailuresMapToPermanentUnauthorized() = runBlocking {
        for (status in listOf(401, 403)) {
            val backend = LocalHttpBackend.start { exchange ->
                exchange.respond(status, """{"error":{"code":"UNAUTHORIZED","message":"no"}}""")
            }
            try {
                val remote = HttpSyncRemoteDataSource(backend.baseUrl)
                assertEquals(
                    SyncPushResult.Failure(SyncFailureKind.UNAUTHORIZED),
                    remote.push(operation()),
                )
            } finally {
                backend.close()
            }
        }
    }

    @Test
    fun serverErrorsMapToRetryableServerTemporary() = runBlocking {
        for (status in listOf(429, 500, 503)) {
            val backend = LocalHttpBackend.start { exchange ->
                exchange.respond(status, """{"error":{"code":"QDRANT_UNAVAILABLE","message":"down"}}""")
            }
            try {
                val remote = HttpSyncRemoteDataSource(backend.baseUrl)
                assertEquals(
                    SyncPushResult.Failure(SyncFailureKind.SERVER_TEMPORARY),
                    remote.push(operation()),
                )
            } finally {
                backend.close()
            }
        }
    }

    @Test
    fun unreachableBackendMapsToRetryableNetwork() = runBlocking {
        // Port 1 on loopback never accepts; connection refused → NETWORK.
        val remote = HttpSyncRemoteDataSource("http://127.0.0.1:1")
        assertEquals(SyncPushResult.Failure(SyncFailureKind.NETWORK), remote.push(operation()))
    }

    @Test
    fun requestBodyCarriesTheFullCloudSchema() = runBlocking {
        var capturedBody: String? = null
        var capturedPath: String? = null
        val backend = LocalHttpBackend.start { exchange ->
            capturedBody = exchange.readBody()
            capturedPath = exchange.requestPath()
            exchange.respond(201, """{"accepted":true}""")
        }
        try {
            val remote = HttpSyncRemoteDataSource(backend.baseUrl)
            remote.push(operation())
        } finally {
            backend.close()
        }

        val body = capturedBody.orEmpty()
        assertEquals("/sync/operations/UPSERT-11111111-1111-4111-8111-111111111111", capturedPath)
        assertTrue(body.contains("\"memoryId\":\"11111111-1111-4111-8111-111111111111\""))
        assertTrue(body.contains("\"contentHash\":\"hash-abc\""))
        assertTrue(body.contains("\"version\":3"))
        assertTrue(body.contains("\"subjectKey\":\"P-101-PROCEDURE\""))
        assertTrue(body.contains("\"syncDecision\":\"SYNC\""))
        assertTrue(body.contains("\"redacted\":false"))
    }

    @Test
    fun redactedPayloadIsFlaggedAsRedactedInTheRequest() = runBlocking {
        var capturedBody: String? = null
        val backend = LocalHttpBackend.start { exchange ->
            capturedBody = exchange.readBody()
            exchange.respond(201, """{"accepted":true}""")
        }
        try {
            val remote = HttpSyncRemoteDataSource(backend.baseUrl)
            remote.push(
                operation {
                    copy(
                        content = "Replaced [redacted] seal at site 7",
                        syncDecision = SyncDecision.SYNC_REDACTED,
                        redacted = true,
                    )
                },
            )
        } finally {
            backend.close()
        }
        val body = capturedBody.orEmpty()
        assertTrue(body.contains("\"syncDecision\":\"SYNC_REDACTED\""))
        assertTrue(body.contains("\"redacted\":true"))
    }

    @Test
    fun localOnlyOperationsAreRefusedBeforeAnyNetworkCall() = runBlocking {
        val backend = LocalHttpBackend.start { exchange ->
            exchange.respond(500, "must not be called")
        }
        try {
            val remote = HttpSyncRemoteDataSource(backend.baseUrl)
            val result = runCatching {
                remote.push(operation { copy(syncDecision = SyncDecision.LOCAL_ONLY) })
            }
            assertTrue("LOCAL_ONLY must throw, not upload", result.isFailure)
        } finally {
            backend.close()
        }
    }
}
