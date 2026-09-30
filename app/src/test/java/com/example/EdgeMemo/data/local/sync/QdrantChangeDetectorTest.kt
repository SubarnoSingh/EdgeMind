package com.example.EdgeMemo.data.local.sync

import com.example.EdgeMemo.core.record.JsonString
import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordOrigin
import com.example.EdgeMemo.core.record.RecordQuery
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.record.SyncDecision
import com.example.EdgeMemo.core.record.SyncState
import com.example.EdgeMemo.core.sync.CanonicalContentHash
import com.example.EdgeMemo.core.sync.ChangeDetectionOutcome
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.core.sync.SyncClassification
import com.example.EdgeMemo.core.sync.SyncFailureKind
import com.example.EdgeMemo.core.sync.SyncOperationId
import com.example.EdgeMemo.core.sync.SyncOperationRecord
import com.example.EdgeMemo.core.sync.SyncOperationType
import com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 12B.7 — change detection over REAL Qdrant persistence.
 *
 * Records live in the production QdrantEdgeRecordStore (real JNI →
 * qdrant-edge); operations live in the production QdrantSyncOperationStore.
 * Every "was enqueued / was not enqueued" assertion is re-read from the
 * shard. No Room, no in-memory store, no mocks for persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QdrantChangeDetectorTest {

    companion object {
        private const val DIMENSION = 4

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    private fun tempDir(): File {
        val dir = File.createTempFile("edgememo-12b7-", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    private fun payload(vararg pairs: Pair<String, String>): Map<String, JsonValue> =
        pairs.associate { (k, v) -> k to JsonValue.fromString(v) }

    private fun record(
        id: RecordId = RecordId.random(),
        version: Int = 1,
        decision: SyncDecision = SyncDecision.SYNC,
        tombstone: Boolean = false,
        deletedAt: Long? = null,
        origin: RecordOrigin = RecordOrigin.LOCAL,
        syncState: SyncState = SyncState.LOCAL,
        contentHash: String? = null,
        lastSyncedVersion: Int? = null,
        redactedTitle: String? = null,
        redactedContent: String? = null,
        values: Map<String, JsonValue> = payload(
            "title" to "Pump seal procedure",
            "content" to "Replace seal on LINE-A",
        ),
    ): Record = Record(
        id = id,
        recordType = RecordType.PROCEDURE,
        entityId = null,
        vector = null,
        payload = values,
        version = version,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
        syncState = syncState,
        syncDecision = decision,
        contentHash = contentHash,
        tombstone = tombstone,
        deletedAt = deletedAt,
        origin = origin,
        redactedTitle = redactedTitle,
        redactedContent = redactedContent,
        lastSyncedVersion = lastSyncedVersion,
    )

    private class Fixture(val dir: File, val recordStore: QdrantEdgeRecordStore, val detector: QdrantChangeDetector, val operations: QdrantSyncOperationStore)

    private fun fixture(dir: File = tempDir()): Fixture = runBlocking {
        val recordStore = QdrantEdgeRecordStore(dir)
        recordStore.ensureReady(DIMENSION)
        recordStore.ensureIndexes()
        val operations = QdrantSyncOperationStore(recordStore)
        val detector = QdrantChangeDetector(recordStore, operations)
        Fixture(dir, recordStore, detector, operations)
    }

    // ------------------------------------------------------------------
    // classification cases 1–8
    // ------------------------------------------------------------------

    @Test
    fun newLocalRecordEnqueuesDeterministicUpsert() = runBlocking {
        val f = fixture()
        try {
            val r = record()
            f.recordStore.upsert(r)
            val outcome = f.detector.detectAndEnqueue(r)

            val enqueued = (outcome as ChangeDetectionOutcome.Enqueued).operation
            assertEquals(SyncOperationType.UPSERT, enqueued.operationType)
            assertEquals(
                "UPSERT:${r.id.uuid}:1",
                enqueued.operationId.value,
            )
            assertEquals(OutboxOperationState.PENDING, enqueued.state)

            // Durable in Qdrant — re-read from the shard.
            val stored = f.operations.findByOperationId(enqueued.operationId)
            assertNotNull(stored)
            assertEquals(enqueued, stored)

            // The record itself moved to sync-state PENDING at the SAME version.
            val reread = f.recordStore.get(r.id)!!
            assertEquals(SyncState.PENDING, reread.syncState)
            assertEquals(1, reread.version)
            // contentHash is stamped so later detection has a stable identity.
            assertEquals(CanonicalContentHash.hash(r.payload), reread.contentHash)
        } finally {
            f.recordStore.close()
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun repeatedDetectionIsIdempotent() = runBlocking {
        val f = fixture()
        try {
            val r = record()
            f.recordStore.upsert(r)
            val first = f.detector.detectAndEnqueue(r)
            val second = f.detector.detectAndEnqueue(
                f.recordStore.get(r.id)!!, // re-read: PENDING + contentHash stamped
            )
            assertTrue(first is ChangeDetectionOutcome.Enqueued)
            val repeated = second as ChangeDetectionOutcome.AlreadyEnqueued
            assertEquals(
                (first as ChangeDetectionOutcome.Enqueued).operation,
                repeated.operation,
            )
            // One operation point, not two.
            assertEquals(1L, f.operations.countByState(OutboxOperationState.PENDING))
        } finally {
            f.recordStore.close()
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun sameVersionSameHashAfterSyncIsDuplicate() = runBlocking {
        val f = fixture()
        try {
            val op = f.detector.detectAndEnqueue(record().also { f.recordStore.upsert(it) })
            val r = (op as ChangeDetectionOutcome.Enqueued).operation
            // Simulate a completed sync via the §7.3 watermark.
            val synced = f.recordStore.get(r.recordId)!!.copy(
                syncState = SyncState.SYNCED,
                lastSyncedVersion = 1,
                lastSyncedContentHash = CanonicalContentHash.hash(f.recordStore.get(r.recordId)!!.payload),
                lastSyncedOperationId = r.operationId.value,
            )
            f.recordStore.upsert(synced)

            val outcome = f.detector.detectAndEnqueue(f.recordStore.get(r.recordId)!!)
            val noChange = outcome as ChangeDetectionOutcome.NoChange
            assertEquals(SyncClassification.DUPLICATE, noChange.classification)
            assertEquals(1L, f.operations.countByState(OutboxOperationState.PENDING)) // nothing new
        } finally {
            f.recordStore.close()
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun sameVersionDifferentContentHashIsConflictAndNeverEnqueued() = runBlocking {
        val f = fixture()
        try {
            val r = record()
            f.recordStore.upsert(r)
            // v1 is cloud-confirmed with hash H1.
            val h1 = CanonicalContentHash.hash(r.payload)
            f.recordStore.upsert(
                r.copy(
                    syncState = SyncState.SYNCED,
                    contentHash = h1,
                    lastSyncedVersion = 1,
                    lastSyncedContentHash = h1,
                ),
            )
            // A write that mutated content WITHOUT bumping the version.
            val mutated = f.recordStore.get(r.id)!!.copy(
                payload = payload("title" to "new", "content" to "new body"),
            )
            f.recordStore.upsert(mutated)
            val outcome = f.detector.detectAndEnqueue(f.recordStore.get(r.id)!!)
            val noChange = outcome as ChangeDetectionOutcome.NoChange
            assertEquals(SyncClassification.CONFLICT, noChange.classification)
            assertNull(f.operations.findByOperationId(SyncOperationId.generate(SyncOperationType.UPSERT, r.id, 1)))
            assertEquals(0L, f.operations.countByState(OutboxOperationState.PENDING))
        } finally {
            f.recordStore.close()
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun higherVersionAfterSyncEnqueuesUpdate() = runBlocking {
        val f = fixture()
        try {
            val r = record()
            f.recordStore.upsert(r)
            f.detector.detectAndEnqueue(r)
            // Mark synced at v1.
            f.recordStore.upsert(f.recordStore.get(r.id)!!.copy(syncState = SyncState.SYNCED, lastSyncedVersion = 1))

            val v2 = f.recordStore.get(r.id)!!.copy(
                version = 2,
                payload = payload("title" to "Pump seal procedure", "content" to "Replace BOTH seals"),
            )
            f.recordStore.upsert(v2)
            val outcome = f.detector.detectAndEnqueue(v2)
            val enqueued = (outcome as ChangeDetectionOutcome.Enqueued).operation
            assertEquals("UPSERT:${r.id.uuid}:2", enqueued.operationId.value)
            assertEquals(2, f.operations.maxOperationVersionFor(r.id)!!)
        } finally {
            f.recordStore.close()
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun lowerVersionThanWatermarkIsStaleAndRefused() = runBlocking {
        val f = fixture()
        try {
            val r = record()
            val stale = r.copy(
                version = 3,
                lastSyncedVersion = 5,
            )
            f.recordStore.upsert(stale)
            val outcome = f.detector.detectAndEnqueue(f.recordStore.get(r.id)!!)
            val noChange = outcome as ChangeDetectionOutcome.NoChange
            assertEquals(SyncClassification.STALE, noChange.classification)
            assertEquals(0L, f.operations.countByState(OutboxOperationState.PENDING))
        } finally {
            f.recordStore.close()
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun tombstonedRecordEnqueuesTombstoneOperationWithDeletedAt() = runBlocking {
        val f = fixture()
        try {
            val r = record()
            f.recordStore.upsert(r)
            f.detector.detectAndEnqueue(f.recordStore.get(r.id)!!)
            f.recordStore.upsert(f.recordStore.get(r.id)!!.copy(syncState = SyncState.SYNCED, lastSyncedVersion = 1))

            val deleted = f.recordStore.softDelete(r.id)
            assertEquals(2, deleted.version)
            val outcome = f.detector.detectAndEnqueue(f.recordStore.get(r.id)!!)
            val tombstoneOp = (outcome as ChangeDetectionOutcome.Enqueued).operation
            assertEquals(SyncOperationType.TOMBSTONE, tombstoneOp.operationType)
            assertEquals("TOMBSTONE:${r.id.uuid}:2", tombstoneOp.operationId.value)
            assertEquals(JsonValue.fromBoolean(true), tombstoneOp.payload["tombstone"])
            assertNotNull(tombstoneOp.payload["deletedAt"])
            // The UPSERT v2 identity must NOT exist; the TOMBSTONE one must.
            assertNull(
                f.operations.findByOperationId(
                    SyncOperationId.generate(SyncOperationType.UPSERT, r.id, 2),
                ),
            )
        } finally {
            f.recordStore.close()
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun alreadyEnqueuedFailedOperationIsReturnedUnchanged() = runBlocking {
        val f = fixture()
        try {
            val r = record()
            f.recordStore.upsert(r)
            val enqueued = (f.detector.detectAndEnqueue(r) as ChangeDetectionOutcome.Enqueued).operation
            f.operations.claimNext(1, 30_000)
            f.operations.markFailed(enqueued.operationId, SyncFailureKind.NETWORK)

            // Re-detect the same version: the FAILED operation is returned as-is.
            val reread = f.recordStore.get(r.id)!!
            val outcome = f.detector.detectAndEnqueue(reread)
            val already = outcome as ChangeDetectionOutcome.AlreadyEnqueued
            assertEquals(OutboxOperationState.FAILED, already.operation.state)
            assertEquals(1, already.operation.attempts)
            assertEquals(1L, f.operations.countByState(OutboxOperationState.FAILED))
            assertEquals(0L, f.operations.countByState(OutboxOperationState.PENDING))
        } finally {
            f.recordStore.close()
            f.dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // policy cases 10–12
    // ------------------------------------------------------------------

    @Test
    fun localOnlyNeverCreatesOperationsAndWithdrawsClaimableOnes() = runBlocking {
        val f = fixture()
        try {
            val r = record()
            f.recordStore.upsert(r)
            val op = (f.detector.detectAndEnqueue(r) as ChangeDetectionOutcome.Enqueued).operation
            assertEquals(1L, f.operations.countByState(OutboxOperationState.PENDING))

            // Same record later re-classified LOCAL_ONLY (§10.4 withdrawal).
            val privateCopy = f.recordStore.get(r.id)!!.copy(syncDecision = SyncDecision.LOCAL_ONLY)
            f.recordStore.upsert(privateCopy)
            val outcome = f.detector.detectAndEnqueue(f.recordStore.get(r.id)!!)
            assertEquals(ChangeDetectionOutcome.LocalOnlyProtected, outcome)
            assertEquals(0L, f.operations.countByState(OutboxOperationState.PENDING))
            assertNull(f.operations.findByOperationId(op.operationId))
        } finally {
            f.recordStore.close()
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun localOnlyFromTheStartProducesNoOperationAtAll() = runBlocking {
        val f = fixture()
        try {
            val r = record(decision = SyncDecision.LOCAL_ONLY)
            f.recordStore.upsert(r)
            assertEquals(
                ChangeDetectionOutcome.LocalOnlyProtected,
                f.detector.detectAndEnqueue(f.recordStore.get(r.id)!!),
            )
            assertEquals(0L, f.operations.countByState(OutboxOperationState.PENDING))
        } finally {
            f.recordStore.close()
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun syncRedactedEnqueuesOnlyTheRedactedRepresentation() = runBlocking {
        val f = fixture()
        try {
            val r = record(
                decision = SyncDecision.SYNC_REDACTED,
                redactedTitle = "Procedure (redacted)",
                redactedContent = "REDACTED: repair procedure P-101 rev 2",
            )
            f.recordStore.upsert(r)
            val op = (f.detector.detectAndEnqueue(r) as ChangeDetectionOutcome.Enqueued).operation
            assertEquals(SyncDecision.SYNC_REDACTED, op.syncDecision)
            assertEquals(true, op.redacted)
            val title = (op.payload["title"] as? JsonString)?.value
            val content = (op.payload["content"] as? JsonString)?.value
            assertEquals("Procedure (redacted)", title)
            assertEquals("REDACTED: repair procedure P-101 rev 2", content)
            // Raw domain content must be absent from the outgoing payload.
            op.payload.forEach { (key, value) ->
                if (value is JsonString) {
                    assertTrue(
                        "raw domain value leaked into sync payload under $key",
                        !value.value.contains("LINE-A"),
                    )
                }
            }
        } finally {
            f.recordStore.close()
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun syncRedactedWithoutRedactedFormRefusesToEnqueue() = runBlocking {
        val f = fixture()
        try {
            val r = record(decision = SyncDecision.SYNC_REDACTED)
            f.recordStore.upsert(r)
            assertEquals(
                ChangeDetectionOutcome.RedactionUnavailable,
                f.detector.detectAndEnqueue(f.recordStore.get(r.id)!!),
            )
            assertEquals(0L, f.operations.countByState(OutboxOperationState.PENDING))
        } finally {
            f.recordStore.close()
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun cloudOriginRecordsNeverProduceEchoOperations() = runBlocking {
        val f = fixture()
        try {
            val r = record(origin = RecordOrigin.CLOUD, syncState = SyncState.SYNCED)
            f.recordStore.upsert(r)
            assertEquals(
                ChangeDetectionOutcome.CloudOriginIgnored,
                f.detector.detectAndEnqueue(f.recordStore.get(r.id)!!),
            )
            assertEquals(0L, f.operations.countByState(OutboxOperationState.PENDING))
        } finally {
            f.recordStore.close()
            f.dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // hash semantics + persistence
    // ------------------------------------------------------------------

    @Test
    fun canonicalHashIgnoresSyncMetadataAndChangesWithContent() = runBlocking {
        val f = fixture()
        try {
            val r = record()
            f.recordStore.upsert(r)
            val before = CanonicalContentHash.hash(f.recordStore.get(r.id)!!.payload)
            val op = (f.detector.detectAndEnqueue(f.recordStore.get(r.id)!!) as ChangeDetectionOutcome.Enqueued).operation
            f.operations.claimNext(1, 30_000)
            f.operations.markAcked(op.operationId, cloudVersion = 1)
            // Simulated watermark write — sync metadata must not change content identity.
            f.recordStore.upsert(
                f.recordStore.get(r.id)!!.copy(
                    syncState = SyncState.SYNCED,
                    lastSyncedVersion = 1,
                    lastSyncedOperationId = op.operationId.value,
                    lastSyncedContentHash = "0".repeat(64),
                    updatedAt = 1_700_000_999_999L,
                ),
            )
            val after = CanonicalContentHash.hash(f.recordStore.get(r.id)!!.payload)
            assertEquals(before, after)

            // Meaningful content change changes the hash.
            val edited = f.recordStore.get(r.id)!!.copy(
                version = 2,
                payload = payload("title" to "Pump seal procedure", "content" to "Replace the O-ring"),
            )
            assertNotEquals(after, CanonicalContentHash.hash(edited.payload))
        } finally {
            f.recordStore.close()
            f.dir.deleteRecursively()
        }
    }

    @Test
    fun detectionResultsSurviveRestart() = runBlocking {
        val f = fixture()
        val r = record()
        f.recordStore.upsert(r)
        val op = (f.detector.detectAndEnqueue(r) as ChangeDetectionOutcome.Enqueued).operation
        f.recordStore.close()

        val dir = f.dir
        val reopened = QdrantEdgeRecordStore(dir)
        reopened.open()
        try {
            val operations = QdrantSyncOperationStore(reopened)
            val detector = QdrantChangeDetector(reopened, operations)
            // Re-read from the shard: the operation is still there.
            assertEquals(op, operations.findByOperationId(op.operationId))
            // And re-detection after restart stays idempotent.
            val outcome = detector.detectAndEnqueue(reopened.get(r.id)!!)
            assertTrue(outcome is ChangeDetectionOutcome.AlreadyEnqueued)
            assertEquals(1L, operations.countByState(OutboxOperationState.PENDING))
        } finally {
            reopened.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun operationPayloadPassesTheFrozenEnvelopeShape() = runBlocking {
        val f = fixture()
        try {
            val r = record()
            f.recordStore.upsert(r)
            val op = (f.detector.detectAndEnqueue(r) as ChangeDetectionOutcome.Enqueued).operation
            // Round-trips through the 12B.2 codec exactly (backend contract).
            val encoded = com.example.EdgeMemo.core.sync.SyncProtocolCodec.encodeOperation(op)
            val decoded = com.example.EdgeMemo.core.sync.SyncProtocolCodec.decodeOperation(encoded)
            assertEquals(op, decoded)
            assertEquals(op.payload["type"]?.let { (it as? JsonString)?.value }, RecordType.PROCEDURE.name)
        } finally {
            f.recordStore.close()
            f.dir.deleteRecursively()
        }
    }

}
