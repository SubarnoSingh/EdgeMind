package com.example.EdgeMemo.data.sync

import com.example.EdgeMemo.core.sync.SyncFailureKind
import com.example.EdgeMemo.core.sync.SyncOperationRecord
import com.example.EdgeMemo.core.sync.SyncProtocolCodec
import com.example.EdgeMemo.core.sync.SyncProtocolResponse
import com.example.EdgeMemo.core.sync.SyncPushOutcome
import com.example.EdgeMemo.core.sync.SyncResponseStatus
import com.example.EdgeMemo.core.sync.QdrantSyncRemote
import com.example.EdgeMemo.data.remote.CloudHttpClient
import java.io.IOException

/**
 * Production [QdrantSyncRemote] for the Phase 12 path.
 *
 * Speaks the EXISTING backend endpoint and the frozen 12B.2 protocol:
 * `PUT /sync/operations/{operationId}` with the exact §20.2 envelope body
 * (`operation_id` present in both path and body — the backend enforces they
 * match) and machine-classifiable `status` responses (§23.1). No parallel
 * API is introduced; the legacy Room-sync request shape remains the
 * backend's rollback path, untouched.
 *
 * A success outcome is returned ONLY when the backend actually answered with
 * the protocol status for that outcome — no fabricated acknowledgements.
 */
class HttpQdrantSyncRemote(
    private val client: CloudHttpClient,
) : QdrantSyncRemote {

    override suspend fun push(operation: SyncOperationRecord): SyncPushOutcome {
        // Defense in depth mirroring the legacy remote: LOCAL_ONLY content may
        // never touch the network even if a caller mis-builds an operation.
        require(operation.syncDecision != com.example.EdgeMemo.core.record.SyncDecision.LOCAL_ONLY) {
            "LOCAL_ONLY operations must never be pushed"
        }
        val body = SyncProtocolCodec.encodeOperation(operation)
        return try {
            val response = client.putJson("/sync/operations/${operation.operationId.value}", body)
            classifySuccess(operation, SyncProtocolCodec.decodeResponse(response))
        } catch (failure: CloudHttpClient.HttpFailure) {
            classifyHttpFailure(operation, failure)
        } catch (_: IOException) {
            SyncPushOutcome.RetryableFailure(SyncFailureKind.NETWORK)
        }
    }

    private fun classifySuccess(
        operation: SyncOperationRecord,
        response: SyncProtocolResponse,
    ): SyncPushOutcome {
        require(response.operationId == operation.operationId) {
            "Backend acknowledged a different operation identity"
        }
        return when (response.status) {
            SyncResponseStatus.APPLIED -> SyncPushOutcome.Applied(
                cloudVersion = response.cloudVersion,
                cloudContentHash = response.cloudContentHash,
                cloudTombstone = response.cloudTombstone,
            )
            SyncResponseStatus.DUPLICATE -> {
                val version = response.cloudVersion
                    ?: return SyncPushOutcome.PermanentFailure(SyncFailureKind.REJECTED)
                val hash = response.cloudContentHash
                    ?: return SyncPushOutcome.PermanentFailure(SyncFailureKind.REJECTED)
                SyncPushOutcome.Duplicate(version, hash)
            }
            // A 2xx carrying STALE/CONFLICT would be a protocol violation;
            // fail closed rather than acknowledge.
            SyncResponseStatus.STALE, SyncResponseStatus.CONFLICT ->
                SyncPushOutcome.PermanentFailure(SyncFailureKind.REJECTED)
        }
    }

    private fun classifyHttpFailure(
        operation: SyncOperationRecord,
        failure: CloudHttpClient.HttpFailure,
    ): SyncPushOutcome {
        if (failure.statusCode == STALE_OR_CONFLICT_STATUS) {
            val response = runCatching { SyncProtocolCodec.decodeResponse(failure.body) }.getOrNull()
                ?: return SyncPushOutcome.PermanentFailure(SyncFailureKind.REJECTED)
            val version = response.cloudVersion
                ?: return SyncPushOutcome.PermanentFailure(SyncFailureKind.REJECTED)
            val hash = response.cloudContentHash
                ?: return SyncPushOutcome.PermanentFailure(SyncFailureKind.REJECTED)
            return when (response.status) {
                SyncResponseStatus.STALE ->
                    SyncPushOutcome.Stale(version, hash, response.cloudTombstone)
                SyncResponseStatus.CONFLICT ->
                    SyncPushOutcome.Conflict(version, hash, response.cloudTombstone)
                else -> SyncPushOutcome.PermanentFailure(SyncFailureKind.REJECTED)
            }
        }
        return when (failure.statusCode) {
            400, 422 -> SyncPushOutcome.PermanentFailure(SyncFailureKind.REJECTED)
            401, 403 -> SyncPushOutcome.PermanentFailure(SyncFailureKind.UNAUTHORIZED)
            in 429..599 -> SyncPushOutcome.RetryableFailure(SyncFailureKind.SERVER_TEMPORARY)
            else -> SyncPushOutcome.RetryableFailure(SyncFailureKind.NETWORK)
        }
    }

    private companion object {
        private const val STALE_OR_CONFLICT_STATUS = 409
    }
}
