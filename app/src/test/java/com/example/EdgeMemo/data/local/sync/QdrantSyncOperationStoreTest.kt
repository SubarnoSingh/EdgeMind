package com.example.EdgeMemo.data.local.sync

import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.SyncDecision
import com.example.EdgeMemo.core.record.getString
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.core.sync.SyncFailureKind
import com.example.EdgeMemo.core.sync.SyncOperationId
import com.example.EdgeMemo.core.sync.SyncOperationRecord
import com.example.EdgeMemo.core.sync.SyncOperationType
import com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 12B.6: the production Qdrant-native sync operation store.
 *
 * Persistence is REAL: QdrantSyncOperationStore → QdrantEdgeRecordStore →
 * NativeBridge JNI → Rust → qdrant-edge 0.8.0 on a temp directory. No Room,
 * no SQLite, no files, no in-memory fallback. Every assertion re-reads from
 * the shard, including after close/reopen (restart recovery).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QdrantSyncOperationStoreTest {

    companion object {
        private const val DIMENSION = 4
        private const val LEASE_MS = 30_000L

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    private val recordId = RecordId.fromString("123e4567-e89b-12d3-a456-426614174000")

    private var currentTime = 1_700_000_000_000L

    private fun tempDir(): File {
        val dir = File.createTempFile("edgememo-12b6-", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    private fun newRecordStore(dir: File): QdrantEdgeRecordStore = QdrantEdgeRecordStore(dir)

    private fun openStore(dir: File): Pair<QdrantEdgeRecordStore, QdrantSyncOperationStore> =
        runBlocking {
            val recordStore = newRecordStore(dir)
            recordStore.ensureReady(DIMENSION)
            recordStore.ensureIndexes()
            recordStore to QdrantSyncOperationStore(recordStore) { currentTime }
        }

    private fun operation(
        version: Int = 1,
        type: SyncOperationType = SyncOperationType.UPSERT,
        state: OutboxOperationState = OutboxOperationState.PENDING,
        attempts: Int = 0,
        lastError: SyncFailureKind? = null,
        leaseUntil: Long? = null,
        decision: SyncDecision = SyncDecision.SYNC,
        redacted: Boolean = false,
        createdAt: Long = currentTime,
        payload: Map<String, JsonValue> = mapOf(
            "payload_title" to JsonValue.fromString("Pump seal"),
            "payload_content" to JsonValue.fromString("Replace seal on LINE-A"),
        ),
    ): SyncOperationRecord = SyncOperationRecord(
        recordId = recordId,
        operationId = SyncOperationId.generate(type, recordId, version),
        operationType = type,
        state = state,
        attempts = attempts,
        lastError = lastError,
        createdAt = createdAt,
        updatedAt = createdAt,
        leaseUntil = leaseUntil,
        version = version,
        syncDecision = decision,
        redacted = redacted,
        payload = payload,
    )

    // ------------------------------------------------------------------
    // Creation, identity, idempotency
    // ------------------------------------------------------------------

    @Test
    fun enqueuePersistsPayloadOnlyOperationAndRoundTrips() = runBlocking {
        val dir = tempDir()
        val (recordStore, store) = openStore(dir)
        try {
            val op = operation()
            val stored = store.enqueue(op)
            assertEquals(op, stored)

            // Re-read through the record layer: a payload-only point.
            val point = recordStore.get(QdrantSyncOperationStore.pointId(op.operationId))
            assertNotNull("operation point must exist in the shard", point)
            assertNull("operation points are payload-only (no vector)", point!!.vector)
            assertEquals(com.example.EdgeMemo.core.record.RecordType.OUTBOX_OP, point.recordType)

            val found = store.findByOperationId(op.operationId)
            assertEquals(op, found)
            assertEquals(
                "Replace seal on LINE-A",
                found!!.payload["payload_content"]?.getString(),
            )
        } finally {
            recordStore.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun operationPointIdIsDeterministicAndEnqueueIsIdempotent() = runBlocking {
        val dir = tempDir()
        val (recordStore, store) = openStore(dir)
        try {
            val op = operation()
            val first = QdrantSyncOperationStore.pointId(op.operationId)
            val second = QdrantSyncOperationStore.pointId(op.operationId)
            assertEquals("point id must be deterministic per operation identity", first, second)

            store.enqueue(op)
            val duplicate = store.enqueue(operation())
            assertEquals(duplicate, op)

            // One point only — duplicate enqueue must not create a second.
            assertEquals(
                1L,
                recordStore.count(
                    com.example.EdgeMemo.core.record.RecordQuery.Count(
                        recordTypes = setOf(com.example.EdgeMemo.core.record.RecordType.OUTBOX_OP),
                    ),
                ),
            )

            // New version → different identity → different point.
            store.enqueue(operation(version = 2))
            assertEquals(2, store.maxOperationVersionFor(recordId))
            assertEquals(2L, store.countByState(OutboxOperationState.PENDING))
        } finally {
            recordStore.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun tombstoneOperationTypeHasSeparateIdentityFromUpsert() = runBlocking {
        val dir = tempDir()
        val (recordStore, store) = openStore(dir)
        try {
            val upsert = store.enqueue(operation(version = 4))
            val tombstone = store.enqueue(operation(version = 4, type = SyncOperationType.TOMBSTONE))
            assertNotEqualsRecords(upsert.operationId.value, tombstone.operationId.value)
            assertEquals(
                "TOMBSTONE:123e4567-e89b-12d3-a456-426614174000:4",
                tombstone.operationId.value,
            )
            assertEquals(2, store.listAll(recordStore).size)
        } finally {
            recordStore.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun localOnlyOperationsCannotExist() {
        // The 12B.2 contract enforces this at construction: no operation
        // record can ever be created for LOCAL_ONLY content.
        val error = runCatching {
            operation(decision = SyncDecision.LOCAL_ONLY)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun syncRedactedRequiresRedactedRepresentationAndPersistsFlag() = runBlocking {
        val dir = tempDir()
        val (recordStore, store) = openStore(dir)
        try {
            assertTrue(
                runCatching {
                    operation(decision = SyncDecision.SYNC_REDACTED, redacted = false)
                }.exceptionOrNull() is IllegalArgumentException,
            )
            val op = operation(decision = SyncDecision.SYNC_REDACTED, redacted = true)
            store.enqueue(op)
            val found = store.findByOperationId(op.operationId)
            assertEquals(op, found)
        } finally {
            recordStore.close()
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // State machine over real persistence
    // ------------------------------------------------------------------

    @Test
    fun claimTransitionsPendingToInFlightWithLease() = runBlocking {
        val dir = tempDir()
        val (recordStore, store) = openStore(dir)
        try {
            val op = store.enqueue(operation())
            currentTime += 1_000
            val claimed = store.claimNext(limit = 5, leaseMs = LEASE_MS)
            assertEquals(1, claimed.size)
            val claimedOp = claimed.first()
            assertEquals(OutboxOperationState.IN_FLIGHT, claimedOp.state)
            assertEquals(currentTime + LEASE_MS, claimedOp.leaseUntil)
            assertNull(claimedOp.lastError)

            // The claim is durable: re-read from the shard.
            val reread = store.findByOperationId(op.operationId)!!
            assertEquals(OutboxOperationState.IN_FLIGHT, reread.state)
            assertEquals(currentTime + LEASE_MS, reread.leaseUntil)

            // A live (unexpired) lease cannot be re-claimed.
            currentTime += 1
            assertTrue(store.claimNext(limit = 5, leaseMs = LEASE_MS).isEmpty())
        } finally {
            recordStore.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun fullRetryLifecyclePendingInFlightFailedInFlightAcked() = runBlocking {
        val dir = tempDir()
        val (recordStore, store) = openStore(dir)
        try {
            val op = store.enqueue(operation())

            // PENDING → IN_FLIGHT
            currentTime += 10
            assertEquals(1, store.claimNext(5, LEASE_MS).size)

            // IN_FLIGHT → FAILED (retryable, budget remains)
            currentTime += 10
            assertTrue(store.markFailed(op.operationId, SyncFailureKind.NETWORK))
            val failed = store.findByOperationId(op.operationId)!!
            assertEquals(OutboxOperationState.FAILED, failed.state)
            assertEquals(1, failed.attempts)
            assertEquals(SyncFailureKind.NETWORK, failed.lastError)
            assertNull(failed.leaseUntil)

            // FAILED → PENDING → IN_FLIGHT (re-claim, identical identity)
            currentTime += 10
            val reclaimed = store.claimNext(5, LEASE_MS)
            assertEquals(1, reclaimed.size)
            assertEquals(op.operationId, reclaimed.first().operationId)
            assertEquals(OutboxOperationState.IN_FLIGHT, reclaimed.first().state)

            // IN_FLIGHT → ACKED with cloud version watermark
            currentTime += 10
            assertTrue(store.markAcked(op.operationId, cloudVersion = 1))
            val acked = store.findByOperationId(op.operationId)!!
            assertEquals(OutboxOperationState.ACKED, acked.state)
            assertEquals(1, acked.lastSyncedVersion)
            assertEquals(1L, store.countByState(OutboxOperationState.ACKED))

            // Terminal operations are immutable: no further transitions.
            assertFalse(store.markFailed(op.operationId, SyncFailureKind.NETWORK))
            assertFalse(store.markDead(op.operationId, SyncFailureKind.REJECTED))
            assertFalse(store.markAcked(op.operationId, cloudVersion = 2))
            // Re-enqueueing returns the ACKED record untouched (append-only identity).
            val requeued = store.enqueue(operation())
            assertEquals(OutboxOperationState.ACKED, requeued.state)
        } finally {
            recordStore.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun permanentFailureMovesInFlightToDead() = runBlocking {
        val dir = tempDir()
        val (recordStore, store) = openStore(dir)
        try {
            val op = store.enqueue(operation())
            store.claimNext(5, LEASE_MS)
            assertTrue(store.markDead(op.operationId, SyncFailureKind.UNAUTHORIZED))
            val dead = store.findByOperationId(op.operationId)!!
            assertEquals(OutboxOperationState.DEAD, dead.state)
            assertEquals(SyncFailureKind.UNAUTHORIZED, dead.lastError)
            assertEquals(1, dead.attempts)
            // DEAD operations are never claimed again.
            assertTrue(store.claimNext(5, LEASE_MS).isEmpty())
            assertFalse(store.markAcked(op.operationId, cloudVersion = 1))
        } finally {
            recordStore.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun illegalTransitionsAreRejected() = runBlocking {
        val dir = tempDir()
        val (recordStore, store) = openStore(dir)
        try {
            val op = store.enqueue(operation())
            // Cannot ack or fail a PENDING operation — it was never claimed.
            assertFalse(store.markAcked(op.operationId, cloudVersion = 1))
            assertFalse(store.markFailed(op.operationId, SyncFailureKind.NETWORK))
            assertFalse(store.markDead(op.operationId, SyncFailureKind.REJECTED))
            // Unknown operations return false, never throw fake success.
            assertFalse(
                store.markAcked(
                    SyncOperationId.generate(SyncOperationType.UPSERT, recordId, 99),
                    cloudVersion = 1,
                ),
            )
            assertEquals(OutboxOperationState.PENDING, store.findByOperationId(op.operationId)!!.state)
        } finally {
            recordStore.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun staleInFlightLeaseIsRecoveredForRetry() = runBlocking {
        val dir = tempDir()
        val (recordStore, store) = openStore(dir)
        try {
            val op = store.enqueue(operation())
            store.claimNext(5, LEASE_MS)

            // Simulate process death: clock passes the lease expiry.
            currentTime += LEASE_MS + 5_000
            assertEquals(1, store.recoverStaleInFlight(currentTime))
            val recovered = store.findByOperationId(op.operationId)!!
            assertEquals(OutboxOperationState.FAILED, recovered.state)
            assertEquals(1, recovered.attempts)
            assertNull(recovered.leaseUntil)

            // Recovered operation is claimable again under the SAME identity.
            val reclaimedCheck = store.claimNext(5, LEASE_MS)
            assertEquals(1, reclaimedCheck.size)
            assertEquals(op.operationId, reclaimedCheck.first().operationId)
            assertEquals(OutboxOperationState.IN_FLIGHT, reclaimedCheck.first().state)
        } finally {
            recordStore.close()
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // Querying, filtering, pagination
    // ------------------------------------------------------------------

    @Test
    fun stateQueriesAreExactAndPaginated() = runBlocking {
        val dir = tempDir()
        val (recordStore, store) = openStore(dir)
        try {
            val ops = (1..5).map { v -> store.enqueue(operation(version = v, createdAt = currentTime + v)) }
            assertEquals(5L, store.countByState(OutboxOperationState.PENDING))
            assertEquals(0L, store.countByState(OutboxOperationState.IN_FLIGHT))
            assertEquals(0L, store.countByState(OutboxOperationState.ACKED))

            // Claim two; states must split exactly.
            val claimed = store.claimNext(limit = 2, leaseMs = LEASE_MS)
            assertEquals(2, claimed.size)
            assertEquals(3L, store.countByState(OutboxOperationState.PENDING))
            assertEquals(2L, store.countByState(OutboxOperationState.IN_FLIGHT))

            // Paginated list over the indexed _state filter.
            val first = store.listByStates(setOf(OutboxOperationState.PENDING), limit = 2, offsetId = null)
            assertEquals(2, first.operations.size)
            assertNotNull(first.nextOffsetId)
            val second = store.listByStates(
                setOf(OutboxOperationState.PENDING),
                limit = 2,
                offsetId = first.nextOffsetId,
            )
            assertEquals(1, second.operations.size)
            assertNull(second.nextOffsetId)
            val seen = (first.operations + second.operations).map { it.operationId.value }.toSet()
            assertEquals(3, seen.size)
            seen.forEach { assertTrue(it.startsWith("UPSERT:")) }

            // Multi-state page (In filter over _state).
            val mixed = store.listByStates(
                setOf(OutboxOperationState.PENDING, OutboxOperationState.IN_FLIGHT),
                limit = 10,
                offsetId = null,
            )
            assertEquals(5, mixed.operations.size)
            assertEquals(ops.size, mixed.operations.map { it.operationId }.toSet().size)
        } finally {
            recordStore.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun operationsSurviveRestartAndIndexesRemainQueryable() = runBlocking {
        val dir = tempDir()
        val (recordStore, store) = openStore(dir)
        val op = store.enqueue(operation())
        store.claimNext(5, LEASE_MS)
        store.markFailed(op.operationId, SyncFailureKind.SERVER_TEMPORARY)
        recordStore.close()

        // Restart: reopen WITHOUT ensureIndexes — persisted indexes + data.
        val reopened = newRecordStore(dir)
        reopened.open()
        try {
            val fresh = QdrantSyncOperationStore(reopened) { currentTime }
            val reread = fresh.findByOperationId(op.operationId)!!
            assertEquals(OutboxOperationState.FAILED, reread.state)
            assertEquals(1, reread.attempts)
            assertEquals(SyncFailureKind.SERVER_TEMPORARY, reread.lastError)
            assertEquals(op.operationId, reread.operationId)
            assertEquals(op.recordId, reread.recordId)
            assertEquals(op.version, reread.version)
            assertEquals(op.payload, reread.payload)

            assertEquals(1L, fresh.countByState(OutboxOperationState.FAILED))
            // Claimable via indexed In(_state) + record type OR:
            val claimed = fresh.claimNext(5, LEASE_MS)
            assertEquals(1, claimed.size)
            assertEquals(OutboxOperationState.IN_FLIGHT, claimed.first().state)
            // Re-claim from FAILED keeps the retry budget; it does not consume one.
            assertEquals(1, fresh.findByOperationId(op.operationId)!!.attempts)
        } finally {
            reopened.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun operationsAreIsolatedFromKnowledgeRecords() = runBlocking {
        val dir = tempDir()
        val (recordStore, store) = openStore(dir)
        try {
            store.enqueue(operation())
            recordStore.upsert(
                com.example.EdgeMemo.core.record.Record(
                    id = RecordId.fromString("00000000-0000-0000-0000-000000000f0f"),
                    recordType = com.example.EdgeMemo.core.record.RecordType.PROCEDURE,
                    entityId = null,
                    vector = FloatArray(DIMENSION) { it.toFloat() },
                    payload = emptyMap(),
                    syncDecision = SyncDecision.SYNC,
                ),
            )
            // Operation queries must never surface knowledge records.
            val page = store.listByStates(setOf(OutboxOperationState.PENDING), limit = 10, offsetId = null)
            assertEquals(1, page.operations.size)
            assertTrue(store.listAll(recordStore).all { it.recordType == com.example.EdgeMemo.core.record.RecordType.OUTBOX_OP })
            assertEquals(2L, recordStore.count(com.example.EdgeMemo.core.record.RecordQuery.Count()))
        } finally {
            recordStore.close()
            dir.deleteRecursively()
        }
    }

    private fun assertNotEqualsRecords(a: String, b: String) {
        assertTrue("expected different identities, both were: $a", a != b)
    }

    private suspend fun QdrantSyncOperationStore.listAll(
        recordStore: QdrantEdgeRecordStore,
    ): List<com.example.EdgeMemo.core.record.Record> =
        recordStore.scroll(
            com.example.EdgeMemo.core.record.RecordQuery.Scroll(
                limit = 50,
                recordTypes = setOf(com.example.EdgeMemo.core.record.RecordType.OUTBOX_OP),
            ),
        ).records
}
