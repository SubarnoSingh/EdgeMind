package com.example.EdgeMemo.data.local.sync

import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.sync.CanonicalContentHash
import com.example.EdgeMemo.core.sync.SyncClassification
import com.example.EdgeMemo.core.sync.SyncOperationId
import com.example.EdgeMemo.core.sync.SyncOperationRecord
import com.example.EdgeMemo.core.sync.SyncProtocolCodec
import com.example.EdgeMemo.core.sync.SyncProtocolResponse
import com.example.EdgeMemo.core.sync.SyncRecordSnapshot
import com.example.EdgeMemo.core.sync.SyncResponseStatus
import com.example.EdgeMemo.testing.LocalHttpBackend
import com.example.EdgeMemo.testing.readBody
import com.example.EdgeMemo.testing.requestUri
import com.example.EdgeMemo.testing.respond
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic in-test cloud that serves the FROZEN Phase 12 §23.1 contract
 * over real HTTP (via the production [com.example.EdgeMemo.data.remote.CloudHttpClient]).
 *
 * This is NOT a substitute for the real backend: the Node backend has its own
 * suite (35 tests) that verifies the same frozen contract server-side. This
 * fixture exists so the ANDROID client path (real HTTP, protocol codecs,
 * version-aware classification, every outcome branch) can be exercised
 * deterministically. The classification here is the production Kotlin
 * [SyncClassification] matrix — the same frozen 12B.1 semantics the backend
 * implements in TypeScript — so client and server cannot silently diverge.
 */
class Phase12CloudFixture {

    data class StoredPoint(
        val operationId: String,
        val version: Int,
        val contentHash: String,
        val tombstone: Boolean,
    )

    val points = mutableMapOf<String, StoredPoint>()
    /** Operations that reached the application path (2xx classification). */
    val deliveredOperationIds = mutableListOf<String>()
    /** Every operation identity PUT to this cloud, including 5xx rejections. */
    val attemptedOperationIds = mutableListOf<String>()
    var rejectedPushes = 0

    /** Number of upcoming pushes answered with 503 before serving normally. */
    val failNext = AtomicInteger(0)
    var unauthorized = false
    var malformedConflict = false

    fun seed(recordId: String, version: Int, contentHash: String, tombstone: Boolean = false) {
        points[recordId] = StoredPoint("SEED:$recordId:$version", version, contentHash, tombstone)
    }

    fun start(): LocalHttpBackend = LocalHttpBackend.start { exchange ->
        val uri = exchange.requestUri()
        val method = exchange.requestMethod
        if (method == "PUT" && uri.startsWith("/sync/operations/")) {
            val pathOperationId = uri.removePrefix("/sync/operations/")
            val op = runCatching { SyncProtocolCodec.decodeOperation(exchange.readBody()) }.getOrNull()
                ?.takeIf { runCatching { SyncOperationId.parse(pathOperationId) == it.operationId }.getOrDefault(false) }
            if (op == null) {
                rejectedPushes++
                exchange.respond(422, """{"error":{"code":"INVALID_REQUEST","message":"envelope rejected"}}""")
                return@start
            }
            attemptedOperationIds += op.operationId.value
            if (failNext.getAndDecrement() > 0) {
                exchange.respond(503, """{"error":{"code":"QDRANT_UNAVAILABLE","message":"transient"}}""")
                return@start
            }
            if (unauthorized) {
                exchange.respond(401, """{"error":{"code":"UNAUTHORIZED","message":"bad device"}}""")
                return@start
            }
            deliver(op, exchange)
            return@start
        }
        exchange.respond(404, """{"error":{"code":"NOT_FOUND","message":"no route"}}""")
    }

    private fun deliver(op: SyncOperationRecord, exchange: com.sun.net.httpserver.HttpExchange) {
        deliveredOperationIds += op.operationId.value
        val recordId = op.recordId.uuid
        val incomingHash = CanonicalContentHash.hash(op.payload)
        val current = points[recordId]

        // The backend's exact-operation dedup (sync.ts), before classification.
        if (current != null && current.operationId == op.operationId.value) {
            exchange.respond(
                200,
                SyncProtocolCodec.encodeResponse(response(op, SyncResponseStatus.DUPLICATE, current.version, current.contentHash, current.tombstone)),
            )
            return
        }

        val classification = if (current == null) {
            SyncClassification.NEW
        } else {
            SyncClassification.classify(
                current = SyncRecordSnapshot(RecordId.fromString(recordId), current.version, current.contentHash, current.tombstone),
                incoming = SyncRecordSnapshot(RecordId.fromString(recordId), op.version, incomingHash, op.operationType.name == "TOMBSTONE"),
            )
        }

        when (classification) {
            SyncClassification.NEW, SyncClassification.UPDATE -> {
                points[recordId] = StoredPoint(op.operationId.value, op.version, incomingHash, op.operationType.name == "TOMBSTONE")
                exchange.respond(
                    if (classification == SyncClassification.NEW) 201 else 200,
                    SyncProtocolCodec.encodeResponse(response(op, SyncResponseStatus.APPLIED, op.version, incomingHash, op.operationType.name == "TOMBSTONE")),
                )
            }
            SyncClassification.DUPLICATE -> {
                exchange.respond(
                    200,
                    SyncProtocolCodec.encodeResponse(response(op, SyncResponseStatus.DUPLICATE, current!!.version, current.contentHash, current.tombstone)),
                )
            }
            SyncClassification.STALE -> {
                // §23.1 row 4/6: the cloud NEVER applies the older write.
                exchange.respond(
                    409,
                    SyncProtocolCodec.encodeResponse(response(op, SyncResponseStatus.STALE, current!!.version, current.contentHash, current.tombstone)),
                )
            }
            SyncClassification.CONFLICT -> {
                // §23.1 row 3: evidence preserved, cloud state unchanged.
                if (malformedConflict) {
                    exchange.respond(400, "not-json")
                    return
                }
                exchange.respond(
                    409,
                    SyncProtocolCodec.encodeResponse(response(op, SyncResponseStatus.CONFLICT, current!!.version, current.contentHash, current.tombstone)),
                )
            }
        }
    }

    private fun response(
        op: SyncOperationRecord,
        status: SyncResponseStatus,
        version: Int,
        hash: String,
        tombstone: Boolean,
    ): SyncProtocolResponse = SyncProtocolResponse(
        operationId = op.operationId,
        recordId = op.recordId,
        status = status,
        cloudVersion = version,
        cloudContentHash = hash,
        cloudTombstone = tombstone,
    )
}
