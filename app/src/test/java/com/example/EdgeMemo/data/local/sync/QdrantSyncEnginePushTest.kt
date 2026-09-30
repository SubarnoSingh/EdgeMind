package com.example.EdgeMemo.data.local.sync

import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.record.SyncDecision
import com.example.EdgeMemo.core.record.SyncState
import com.example.EdgeMemo.core.sync.ChangeDetectionOutcome
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.core.sync.SyncFailureKind
import com.example.EdgeMemo.core.sync.SyncOperationId
import com.example.EdgeMemo.core.sync.SyncOperationType
import com.example.EdgeMemo.data.remote.CloudHttpClient
import com.example.EdgeMemo.data.sync.HttpQdrantSyncRemote
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 12B.8 — local → cloud through the Qdrant-native store.
 *
 * Integration surface (all REAL, nothing mocked for persistence):
 *   QdrantEdgeRecordStore / QdrantSyncOperationStore → JNI → qdrant-edge
 *   HttpQdrantSyncRemote → CloudHttpClient → real HTTP → protocol fixture
 *   serving the frozen §23.1 contract with the production classifier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QdrantSyncEnginePushTest {

    companion object {
        private const val DIMENSION = 4

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    private var currentTime = 1_700_000_000_000L

    private inner class EngineFixture {
        val dir: File = run {
            val d = File.createTempFile("edgememo-12b8-", "")
            d.delete(); d.mkdirs(); d
        }
        val cloud = Phase12CloudFixture()
        val backend = cloud.start()
        var recordStore: QdrantEdgeRecordStoreHandle = openRecordStore(dir)
        var engine: DefaultQdrantSyncEngine = buildEngine(recordStore.store)

        fun reopen() {
            runBlocking { recordStore.store.close() }
            recordStore = openRecordStore(dir, reopen = true)
            engine = buildEngine(recordStore.store)
        }

        private fun buildEngine(store: com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore) =
            DefaultQdrantSyncEngine(
                recordStore = store,
                operationStore = recordStore.operations,
                detector = recordStore.detector,
                remote = HttpQdrantSyncRemote(CloudHttpClient(backend.baseUrl)),
                cloudKnowledge = UnreachableCloudKnowledge(),
                clock = { currentTime },
                leaseMs = 30_000L,
                maxAttempts = 2,
            )
    }

    private class QdrantEdgeRecordStoreHandle(
        val store: com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore,
        val operations: QdrantSyncOperationStore,
        val detector: QdrantChangeDetector,
    )

    private fun openRecordStore(dir: File, reopen: Boolean = false): QdrantEdgeRecordStoreHandle {
        val store = com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(dir)
        runBlocking {
            if (reopen) store.open() else {
                store.ensureReady(DIMENSION)
                store.ensureIndexes()
            }
        }
        val operations = QdrantSyncOperationStore(store) { currentTime }
        return QdrantEdgeRecordStoreHandle(store, operations, QdrantChangeDetector(store, operations) { currentTime })
    }

    private fun record(
        id: RecordId = RecordId.random(),
        version: Int = 1,
        content: String = "Replace seal on LINE-A",
        decision: SyncDecision = SyncDecision.SYNC,
    ): Record = Record(
        id = id,
        recordType = RecordType.PROCEDURE,
        entityId = null,
        vector = null,
        payload = mapOf(
            "title" to JsonValue.fromString("Pump seal procedure"),
            "content" to JsonValue.fromString(content),
        ),
        version = version,
        createdAt = currentTime,
        updatedAt = currentTime,
        syncDecision = decision,
    )

    @Test
    fun pendingUpsertIsDeliveredAckedAndWatermarked() = runBlocking {
        val f = EngineFixture()
        try {
            val r = record()
            f.recordStore.store.upsert(r)
            val op = (f.engine.enqueueIfChanged(f.recordStore.store.get(r.id)!!)
                as ChangeDetectionOutcome.Enqueued).operation

            val summary = f.engine.pushPending(maxOperations = 5)
            assertEquals(1, summary.processed)
            assertEquals(1, summary.acked)
            assertEquals(0L, summary.remaining)

            // Operation ACKED — re-read from Qdrant.
            val acked = f.recordStore.operations.findByOperationId(op.operationId)!!
            assertEquals(OutboxOperationState.ACKED, acked.state)

            // §7.3 watermark on the knowledge record — re-read from Qdrant.
            val synced = f.recordStore.store.get(r.id)!!
            assertEquals(SyncState.SYNCED, synced.syncState)
            assertEquals(1, synced.lastSyncedVersion)
            assertEquals(op.operationId.value, synced.lastSyncedOperationId)
            assertNotNull(synced.lastSyncedContentHash)
            assertEquals(1, synced.version)

            // The cloud actually applied the write, once.
            assertEquals(1, f.cloud.points.size)
            assertEquals(listOf(op.operationId.value), f.cloud.deliveredOperationIds)

            // Idempotent repeat: nothing left to send.
            val second = f.engine.pushPending(maxOperations = 5)
            assertEquals(0, second.processed)
            assertEquals(listOf(op.operationId.value), f.cloud.deliveredOperationIds)
        } finally {
            f.backend.close()
            runCatching { f.recordStore.store.close() }
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun tombstoneOperationIsDeliveredAndAcked() = runBlocking {
        val f = EngineFixture()
        try {
            val r = record()
            f.recordStore.store.upsert(r)
            f.engine.enqueueIfChanged(r)
            f.engine.pushPending(5)
            // Update the record so detection sees a synced v1.
            f.recordStore.store.upsert(
                f.recordStore.store.get(r.id)!!.copy(syncState = SyncState.SYNCED, lastSyncedVersion = 1),
            )

            f.recordStore.store.softDelete(r.id) // v2 tombstone
            val op = (f.engine.enqueueIfChanged(f.recordStore.store.get(r.id)!!)
                as ChangeDetectionOutcome.Enqueued).operation
            assertEquals(SyncOperationType.TOMBSTONE, op.operationType)

            val summary = f.engine.pushPending(5)
            assertEquals(1, summary.acked)
            assertEquals(OutboxOperationState.ACKED, f.recordStore.operations.findByOperationId(op.operationId)!!.state)
            assertTrue(f.cloud.points[r.id.uuid]!!.tombstone)
            assertEquals(2, f.cloud.points[r.id.uuid]!!.version)
        } finally {
            f.backend.close()
            runCatching { f.recordStore.store.close() }
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun cloudDuplicateIsAcknowledgedWithoutSecondWrite() = runBlocking {
        val f = EngineFixture()
        try {
            val r = record()
            f.recordStore.store.upsert(r)
            val op = (f.engine.enqueueIfChanged(f.recordStore.store.get(r.id)!!)
                as ChangeDetectionOutcome.Enqueued).operation

            // Seed the cloud to exactly what this operation will deliver so
            // the fixture classifies the push as the idempotent DUPLICATE.
            val hash = com.example.EdgeMemo.core.sync.CanonicalContentHash.hash(op.payload)
            f.cloud.seed(r.id.uuid, op.version, hash)

            val summary = f.engine.pushPending(5)
            assertEquals(1, summary.acked)
            assertEquals(OutboxOperationState.ACKED, f.recordStore.operations.findByOperationId(op.operationId)!!.state)
            // The seed was untouched in content, and only ONE delivery happened.
            assertEquals(1, f.cloud.deliveredOperationIds.size)
            assertEquals(hash, f.cloud.points[r.id.uuid]!!.contentHash)
        } finally {
            f.backend.close()
            runCatching { f.recordStore.store.close() }
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun transientFailureRetriesWithIdenticalOperationId() = runBlocking {
        val f = EngineFixture()
        try {
            val r = record()
            f.recordStore.store.upsert(r)
            val op = (f.engine.enqueueIfChanged(f.recordStore.store.get(r.id)!!)
                as ChangeDetectionOutcome.Enqueued).operation

            f.cloud.failNext.set(1)
            val first = f.engine.pushPending(5)
            assertEquals(1, first.failed)
            val failed = f.recordStore.operations.findByOperationId(op.operationId)!!
            assertEquals(OutboxOperationState.FAILED, failed.state)
            assertEquals(1, failed.attempts)
            assertEquals(SyncFailureKind.SERVER_TEMPORARY, failed.lastError)

            val second = f.engine.pushPending(5)
            assertEquals(1, second.acked)
            val acked = f.recordStore.operations.findByOperationId(op.operationId)!!
            assertEquals(OutboxOperationState.ACKED, acked.state)
            // Retry re-delivered the SAME identity (§18 case 3), not a new op.
            assertEquals(listOf(op.operationId.value, op.operationId.value), f.cloud.attemptedOperationIds)
            assertEquals(listOf(op.operationId.value), f.cloud.deliveredOperationIds)
        } finally {
            f.backend.close()
            runCatching { f.recordStore.store.close() }
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun retryBudgetExhaustionMovesOperationToDead() = runBlocking {
        val f = EngineFixture()
        try {
            val r = record()
            f.recordStore.store.upsert(r)
            val op = (f.engine.enqueueIfChanged(f.recordStore.store.get(r.id)!!)
                as ChangeDetectionOutcome.Enqueued).operation

            f.cloud.failNext.set(2) // maxAttempts = 2
            f.engine.pushPending(5) // FAILED attempts=1
            val second = f.engine.pushPending(5) // attempts+1 >= 2 → DEAD
            assertEquals(1, second.dead)
            val dead = f.recordStore.operations.findByOperationId(op.operationId)!!
            assertEquals(OutboxOperationState.DEAD, dead.state)
            assertEquals(2, dead.attempts)
            // DEAD operations are never claimed again.
            assertEquals(0, f.engine.pushPending(5).processed)
        } finally {
            f.backend.close()
            runCatching { f.recordStore.store.close() }
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun permanentFailureKillsTheOperation() = runBlocking {
        val f = EngineFixture()
        try {
            val r = record()
            f.recordStore.store.upsert(r)
            val op = (f.engine.enqueueIfChanged(f.recordStore.store.get(r.id)!!)
                as ChangeDetectionOutcome.Enqueued).operation
            f.cloud.unauthorized = true
            val summary = f.engine.pushPending(5)
            assertEquals(1, summary.dead)
            val dead = f.recordStore.operations.findByOperationId(op.operationId)!!
            assertEquals(OutboxOperationState.DEAD, dead.state)
            assertEquals(SyncFailureKind.UNAUTHORIZED, dead.lastError)
            f.recordStore.store.get(r.id)!!.let {
                assertTrue("record must not be marked synced after a permanent failure", it.syncState != SyncState.SYNCED)
            }
        } finally {
            f.backend.close()
            runCatching { f.recordStore.store.close() }
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun staleCloudStateIsNeverOverwritten() = runBlocking {
        val f = EngineFixture()
        try {
            val r = record()
            f.recordStore.store.upsert(r)
            val op = (f.engine.enqueueIfChanged(f.recordStore.store.get(r.id)!!)
                as ChangeDetectionOutcome.Enqueued).operation

            // Cloud is ahead: v9 exists. §23.1 row 4 → 409 STALE, no apply.
            f.cloud.seed(r.id.uuid, 9, "a".repeat(64))
            val summary = f.engine.pushPending(5)
            assertEquals(1, summary.stale)
            assertEquals(1, summary.dead)
            val dead = f.recordStore.operations.findByOperationId(op.operationId)!!
            assertEquals(OutboxOperationState.DEAD, dead.state)
            // Cloud version 9 untouched; local record untouched.
            assertEquals(9, f.cloud.points[r.id.uuid]!!.version)
            assertEquals(1, f.recordStore.store.get(r.id)!!.version)
            assertTrue(f.recordStore.store.get(r.id)!!.lastSyncedVersion == null)
        } finally {
            f.backend.close()
            runCatching { f.recordStore.store.close() }
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun pushConflictDiesAndRecordsEvidenceWithoutResolution() = runBlocking {
        val f = EngineFixture()
        try {
            val r = record()
            f.recordStore.store.upsert(r)
            val op = (f.engine.enqueueIfChanged(f.recordStore.store.get(r.id)!!)
                as ChangeDetectionOutcome.Enqueued).operation

            // Cloud holds the SAME version with a different hash → CONFLICT.
            f.cloud.seed(r.id.uuid, op.version, "b".repeat(64))
            val summary = f.engine.pushPending(5)
            assertEquals(1, summary.conflicts)
            assertEquals(1, summary.dead)
            assertEquals(OutboxOperationState.DEAD, f.recordStore.operations.findByOperationId(op.operationId)!!.state)
            // The contradictory cloud state must remain untouched (§23.1 row 3).
            assertEquals("b".repeat(64), f.cloud.points[r.id.uuid]!!.contentHash)
            // A conflict record exists — recording only, never resolution.
            val stored = f.recordStore.store.get(r.id)!!
            assertNotNull(stored.contentHash)
            val expectedId = com.example.EdgeMemo.data.local.sync.QdrantConflictRecorder.conflictPointId(
                subject = "",
                localRecordId = r.id.uuid,
                localContentHash = stored.contentHash!!,
                incomingRecordId = r.id.uuid,
                incomingContentHash = "b".repeat(64),
            )
            val conflict = f.recordStore.store.get(expectedId)
            assertNotNull("push CONFLICT must durably record evidence", conflict)
            assertEquals(RecordType.CONFLICT, conflict!!.recordType)
            assertEquals("PUSH_CONFLICT", (conflict.payload["reason"] as? com.example.EdgeMemo.core.record.JsonString)?.value)
            assertEquals("UNRESOLVED", (conflict.payload["state"] as? com.example.EdgeMemo.core.record.JsonString)?.value)
        } finally {
            f.backend.close()
            runCatching { f.recordStore.store.close() }
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun staleInFlightIsRecoveredThenCompletedAfterRestart() = runBlocking {
        val f = EngineFixture()
        try {
            val r = record()
            f.recordStore.store.upsert(r)
            val op = (f.engine.enqueueIfChanged(f.recordStore.store.get(r.id)!!)
                as ChangeDetectionOutcome.Enqueued).operation

            // Claim explicitly, then die mid-flight WITHOUT acknowledging:
            f.recordStore.operations.claimNext(5, 30_000L)
            currentTime += 60_000 // lease expiry

            // Simulated restart with the claimed operation stranded IN_FLIGHT.
            f.reopen()
            val stranded = f.recordStore.operations.findByOperationId(op.operationId)!!
            assertEquals(OutboxOperationState.IN_FLIGHT, stranded.state)

            val summary = f.engine.pushPending(5)
            assertEquals(1, summary.recovered)
            assertEquals(1, summary.acked)
            assertEquals(OutboxOperationState.ACKED, f.recordStore.operations.findByOperationId(op.operationId)!!.state)
            // Recovered via the SAME identity (§18 case 3).
            assertEquals(1, f.cloud.deliveredOperationIds.count { it == op.operationId.value })
        } finally {
            f.backend.close()
            runCatching { f.recordStore.store.close() }
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun pendingOperationsSurviveRestartAndDrainLater() = runBlocking {
        val f = EngineFixture()
        try {
            val records = (1..3).map { record(content = "Procedure $it") }
            records.forEach { f.recordStore.store.upsert(it) }
            records.forEach { f.engine.enqueueIfChanged(f.recordStore.store.get(it.id)!!) }
            f.recordStore.store.close() // abrupt "restart" (flush already done per write)

            f.reopen()
            val summary = f.engine.pushPending(maxOperations = 2)
            assertEquals(2, summary.processed)
            assertEquals(2, summary.acked)
            assertEquals(1L, summary.remaining)
            val final = f.engine.pushPending(5)
            assertEquals(1, final.acked)
            assertEquals(0L, final.remaining)
        } finally {
            f.backend.close()
            runCatching { f.recordStore.store.close() }
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun multipleOperationsDrainInBoundedBatches() = runBlocking {
        val f = EngineFixture()
        try {
            val ids = (1..4).map { rid -> record(content = "rev $rid").also { f.recordStore.store.upsert(it) } }
            val ops = ids.map { (f.engine.enqueueIfChanged(f.recordStore.store.get(it.id)!!) as ChangeDetectionOutcome.Enqueued).operation }
            val first = f.engine.pushPending(3)
            assertEquals(3, first.processed)
            assertEquals(1L, first.remaining)
            val second = f.engine.pushPending(3)
            assertEquals(1, second.acked)
            ops.forEach { op ->
                assertEquals(OutboxOperationState.ACKED, f.recordStore.operations.findByOperationId(op.operationId)!!.state)
            }
        } finally {
            f.backend.close()
            runCatching { f.recordStore.store.close() }
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun localOnlyContentNeverReachesTheCloud() = runBlocking {
        val f = EngineFixture()
        try {
            val r = record(decision = SyncDecision.LOCAL_ONLY, content = "Gate code 4821")
            f.recordStore.store.upsert(r)
            assertEquals(
                ChangeDetectionOutcome.LocalOnlyProtected,
                f.engine.enqueueIfChanged(f.recordStore.store.get(r.id)!!),
            )
            val summary = f.engine.pushPending(5)
            assertEquals(0, summary.processed)
            assertTrue(f.cloud.deliveredOperationIds.isEmpty())
            assertTrue(f.cloud.points.isEmpty())
        } finally {
            f.backend.close()
            runCatching { f.recordStore.store.close() }
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun malformedConflictBodyFailsClosedWithoutFakeAck() = runBlocking {
        val f = EngineFixture()
        try {
            val r = record()
            f.recordStore.store.upsert(r)
            val op = (f.engine.enqueueIfChanged(f.recordStore.store.get(r.id)!!)
                as ChangeDetectionOutcome.Enqueued).operation
            f.cloud.seed(r.id.uuid, 1, "c".repeat(64))
            f.cloud.malformedConflict = true
            val summary = f.engine.pushPending(5)
            // 400 with an unparseable body must not become an acknowledgement.
            assertEquals(0, summary.acked)
            val after = f.recordStore.operations.findByOperationId(op.operationId)!!
            assertTrue(after.state == OutboxOperationState.DEAD || after.state == OutboxOperationState.FAILED)
        } finally {
            f.backend.close()
            runCatching { f.recordStore.store.close() }
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun tamperedPayloadForIdenticalIdentityIsNeverSilentlyApplied() = runBlocking {
        val f = EngineFixture()
        try {
            val r = record()
            f.recordStore.store.upsert(r)
            val op = (f.engine.enqueueIfChanged(f.recordStore.store.get(r.id)!!)
                as ChangeDetectionOutcome.Enqueued).operation

            // Cloud holds the true v1; a same-identity payload mutation is
            // detected by the frozen hash rule, never applied or acknowledged.
            val trueHash = com.example.EdgeMemo.core.sync.CanonicalContentHash.hash(op.payload)
            f.cloud.seed(r.id.uuid, op.version, trueHash)
            val tampered = op.copy(payload = op.payload + ("injected" to JsonValue.fromString("x")))

            val outcome = HttpQdrantSyncRemote(CloudHttpClient(f.backend.baseUrl)).push(tampered)
            assertTrue(
                "tampered payload must classify as Conflict, got ${outcome::class.simpleName}",
                outcome is com.example.EdgeMemo.core.sync.SyncPushOutcome.Conflict,
            )
            assertEquals(trueHash, f.cloud.points[r.id.uuid]!!.contentHash)
            // A raw remote call never touches local operation state.
            assertEquals(OutboxOperationState.PENDING, f.recordStore.operations.findByOperationId(op.operationId)!!.state)
        } finally {
            f.backend.close()
            runCatching { f.recordStore.store.close() }
            f.dir.deleteRecursively()
        }
    }
}

private class UnreachableCloudKnowledge : com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource {
    override suspend fun pullKnowledge(cursor: String?): com.example.EdgeMemo.domain.cloud.CloudKnowledgeBatch =
        throw com.example.EdgeMemo.core.common.EdgeError.CloudUnavailable("push-focused fixture: pull not configured")
}
