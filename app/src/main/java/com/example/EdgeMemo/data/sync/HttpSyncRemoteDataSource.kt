package com.example.EdgeMemo.data.sync

import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.sync.SyncFailureKind
import com.example.EdgeMemo.core.sync.SyncOperation
import com.example.EdgeMemo.core.sync.SyncPushResult
import com.example.EdgeMemo.data.remote.CloudHttpClient
import com.example.EdgeMemo.domain.sync.SyncRemoteDataSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real Phase-8 sync remote: Android → EdgeMind backend → Qdrant Cloud. The
 * backend owns the Qdrant credentials; this class only knows the backend URL.
 *
 * Status mapping preserves the existing engine semantics: 400/422 → REJECTED
 * (permanent → DEAD), 401/403 → UNAUTHORIZED (permanent), 429/5xx →
 * SERVER_TEMPORARY (retryable), transport errors → NETWORK (retryable).
 * Success is returned ONLY on a 2xx backend acknowledgement of a real Qdrant
 * write — never fabricated.
 */
class HttpSyncRemoteDataSource(
    private val baseUrl: String,
    private val client: CloudHttpClient = CloudHttpClient(baseUrl),
) : SyncRemoteDataSource {

    override suspend fun push(operation: SyncOperation): SyncPushResult {
        if (baseUrl.isBlank()) {
            return SyncPushResult.Failure(SyncFailureKind.SOURCE_UNAVAILABLE)
        }
        // Defense in depth: LOCAL_ONLY has no business being here at all, and
        // it must fail before any network I/O, never silently upload.
        check(operation.syncDecision != SyncDecision.LOCAL_ONLY) { "LOCAL_ONLY can never be pushed" }
        return try {
            client.putJson("/sync/operations/${operation.operationId}", operation.toJson().toString())
            SyncPushResult.Success
        } catch (e: CloudHttpClient.HttpFailure) {
            SyncPushResult.Failure(classify(e.statusCode))
        } catch (e: Exception) {
            SyncPushResult.Failure(SyncFailureKind.NETWORK)
        }
    }

    private fun classify(status: Int): SyncFailureKind = when (status) {
        400, 422 -> SyncFailureKind.REJECTED
        401, 403 -> SyncFailureKind.UNAUTHORIZED
        429, in 500..599 -> SyncFailureKind.SERVER_TEMPORARY
        else -> SyncFailureKind.NETWORK
    }

    private fun SyncOperation.toJson(): JSONObject {
        val memory = JSONObject()
            .put("syncDecision", syncDecision.name)
            .put("origin", origin.name)
            .put("redacted", redacted)
            .put("version", version)
            .put("contentHash", contentHash)
            .put("subjectKey", subjectKey ?: JSONObject.NULL)
            .put("type", type.name)
            .put("tags", JSONArray(tags))
            .put("chunkId", chunkId ?: JSONObject.NULL)
            .put("source", source)
            .put("supersedes", supersedes ?: JSONObject.NULL)
            .put("tombstone", tombstone)
            .put("updatedAt", updatedAt)
            .put("metadata", JSONObject(metadata))

        // Defense in depth is applied by push(); the JSON body is the
        // policy-sanctioned representation produced by the engine.
        return JSONObject()
            .put("operationId", operationId)
            .put("memoryId", memoryId)
            .put("operationType", operationType.name)
            .put("title", title)
            .put("content", content)
            .put("memory", memory)
    }
}
