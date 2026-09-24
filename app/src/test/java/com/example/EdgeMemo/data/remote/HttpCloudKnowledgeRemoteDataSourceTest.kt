package com.example.EdgeMemo.data.remote

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.data.cloud.HttpCloudKnowledgeRemoteDataSource
import com.example.EdgeMemo.testing.LocalHttpBackend
import com.example.EdgeMemo.testing.requestUri
import com.example.EdgeMemo.testing.respond
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real HTTP cloud-knowledge pull against a deterministic in-test backend.
 * Verifies 1:1 mapping onto the Phase 7 CloudKnowledgeBatch contract the
 * ingestor/classifier/conflict system consumes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HttpCloudKnowledgeRemoteDataSourceTest {

    private val itemJson = """
        {
          "memoryId": "22222222-2222-4222-8222-222222222222",
          "subjectKey": "FT-983.3",
          "title": "FT-983.3 procedure",
          "content": "FT-983.3 procedure revision 4.",
          "contentHash": "hash-ft",
          "version": 4,
          "updatedAt": 1700000000000,
          "origin": "CLOUD",
          "authority": "central-engineering",
          "supersedes": null,
          "tombstone": false,
          "metadata": {"kind": "procedure"}
        }
    """.trimIndent()

    @Test
    fun parsesItemsCursorAndMetadataIntoThePhase7Contract() = runBlocking {
        var requestedPath: String? = null
        val backend = LocalHttpBackend.start { exchange ->
            requestedPath = exchange.requestUri()
            exchange.respond(200, """{"items":[$itemJson],"nextCursor":"next-offset-1"}""")
        }
        try {
            val remote = HttpCloudKnowledgeRemoteDataSource(backend.baseUrl)
            val batch = remote.pullKnowledge(null)

            assertEquals(1, batch.items.size)
            val item = batch.items.single()
            assertEquals("22222222-2222-4222-8222-222222222222", item.memoryId)
            assertEquals("FT-983.3", item.subjectKey)
            assertEquals("FT-983.3 procedure", item.title)
            assertEquals("hash-ft", item.contentHash)
            assertEquals(4, item.version)
            assertEquals("CLOUD", item.origin)
            assertEquals("central-engineering", item.authority)
            assertNull(item.supersedes)
            assertEquals(false, item.tombstone)
            assertEquals(mapOf("kind" to "procedure"), item.metadata)
            assertEquals("next-offset-1", batch.nextCursor)
            assertEquals("/knowledge?limit=50", requestedPath)
            assertTrue(!requestedPath.orEmpty().contains("cursor="))
        } finally {
            backend.close()
        }
    }

    @Test
    fun cursorIsPassedThroughOpaquely() = runBlocking {
        var requestedPath: String? = null
        val backend = LocalHttpBackend.start { exchange ->
            requestedPath = exchange.requestUri()
            exchange.respond(200, """{"items":[],"nextCursor":null}""")
        }
        try {
            val remote = HttpCloudKnowledgeRemoteDataSource(backend.baseUrl)
            val batch = remote.pullKnowledge("cursor-1")
            assertTrue(requestedPath.orEmpty().contains("cursor=cursor-1"))
            assertNull(batch.nextCursor)
        } finally {
            backend.close()
        }
    }

    @Test
    fun backendFailureSurfacesAsCloudUnavailable() = runBlocking {
        val backend = LocalHttpBackend.start { exchange ->
            exchange.respond(503, """{"error":{"code":"QDRANT_UNAVAILABLE","message":"down"}}""")
        }
        try {
            val remote = HttpCloudKnowledgeRemoteDataSource(backend.baseUrl)
            val result = runCatching { remote.pullKnowledge(null) }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is EdgeError.CloudUnavailable)
        } finally {
            backend.close()
        }
    }

    @Test
    fun unreachableBackendSurfacesAsCloudUnavailable() = runBlocking {
        val remote = HttpCloudKnowledgeRemoteDataSource("http://127.0.0.1:1")
        val result = runCatching { remote.pullKnowledge(null) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is EdgeError.CloudUnavailable)
    }

    @Test
    fun malformedBatchSurfacesAsCloudUnavailable() = runBlocking {
        val backend = LocalHttpBackend.start { exchange ->
            exchange.respond(200, "not json at all")
        }
        try {
            val remote = HttpCloudKnowledgeRemoteDataSource(backend.baseUrl)
            val result = runCatching { remote.pullKnowledge(null) }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is EdgeError.CloudUnavailable)
        } finally {
            backend.close()
        }
    }
}
