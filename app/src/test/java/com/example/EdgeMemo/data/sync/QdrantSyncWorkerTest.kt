package com.example.EdgeMemo.data.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.record.SyncDecision
import com.example.EdgeMemo.core.record.SyncState
import com.example.EdgeMemo.core.sync.ChangeDetectionOutcome
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.core.sync.QdrantSyncEngine
import com.example.EdgeMemo.core.sync.QdrantSyncRemote
import com.example.EdgeMemo.core.sync.SyncOperationRecord
import com.example.EdgeMemo.core.sync.SyncOperationType
import com.example.EdgeMemo.core.sync.SyncPullSummary
import com.example.EdgeMemo.core.sync.SyncRunSummary
import com.example.EdgeMemo.core.sync.ReconciliationSummary
import com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore
import com.example.EdgeMemo.data.local.sync.DefaultQdrantSyncEngine
import com.example.EdgeMemo.data.local.sync.Phase12CloudFixture
import com.example.EdgeMemo.data.local.sync.QdrantChangeDetector
import com.example.EdgeMemo.data.local.sync.QdrantConflictResolver
import com.example.EdgeMemo.data.local.sync.QdrantSyncOperationStore
import com.example.EdgeMemo.data.remote.CloudHttpClient
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeBatch
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 12B.13 — WorkManager regression over the REAL Qdrant-native stack.
 *
 * Persistence is the production JNI shard (no fake stores); cloud push is
 * real HTTP through the production remote against the frozen §23.1 protocol
 * fixture (same classifier as the shipped backend). The pull source is a
 * deterministic fixture implementing the existing cloud contract — the
 * production HTTP pull adapter itself is covered by 12B.9 pull tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QdrantSyncWorkerTest {

    companion object {
        private const val DIMENSION = 4

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    private var currentTime = 1_700_000_000_000L

    /** qdrant-edge shards pre-allocate segment files; leaking these temp
     *  directories across a full suite run exhausts tmpfs. Always removed. */
    private val cleanupDirs = mutableListOf<File>()

    @org.junit.After
    fun tearDown() {
        for (dir in cleanupDirs) {
            runCatching { dir.deleteRecursively() }
        }
        cleanupDirs.clear()
    }

    private class StubPull : CloudKnowledgeRemoteDataSource {
        val batches = ArrayDeque<CloudKnowledgeBatch>()
        val cursorsSeen = mutableListOf<String?>()
        var unavailable = false

        override suspend fun pullKnowledge(cursor: String?): CloudKnowledgeBatch {
            cursorsSeen += cursor
            if (unavailable) throw EdgeError.CloudUnavailable("fixture: cloud unavailable")
            return batches.removeFirstOrNull() ?: CloudKnowledgeBatch(emptyList())
        }
    }

    private inner class Fixture(
        private val remote: QdrantSyncRemote? = null,
    ) {
        val dir: File = run {
            val d = File.createTempFile("edgememo-12b13-", "")
            d.delete(); d.mkdirs(); d
        }

        init {
            cleanupDirs += dir
        }
        val cloud = Phase12CloudFixture()
        val backend = cloud.start()
        val pull = StubPull()
        lateinit var store: QdrantEdgeRecordStore
        lateinit var operations: QdrantSyncOperationStore
        lateinit var detector: QdrantChangeDetector
        lateinit var engine: DefaultQdrantSyncEngine
        lateinit var conflicts: QdrantConflictResolver

        init {
            open()
        }

        fun open() {
            store = QdrantEdgeRecordStore(dir)
            runBlocking {
                if (!store.isOpen && File(dir, "edge_config.json").isFile) {
                    store.open()
                } else if (!store.isOpen) {
                    store.ensureReady(DIMENSION)
                    store.ensureIndexes()
                }
            }
            operations = QdrantSyncOperationStore(store) { currentTime }
            detector = QdrantChangeDetector(store, operations) { currentTime }
            engine = DefaultQdrantSyncEngine(
                recordStore = store,
                operationStore = operations,
                detector = detector,
                remote = remote ?: HttpQdrantSyncRemote(CloudHttpClient(backend.baseUrl)),
                cloudKnowledge = pull,
                clock = { currentTime },
                leaseMs = 30_000L,
                maxAttempts = 5,
            )
            conflicts = QdrantConflictResolver(store, detector) { currentTime }
        }

        /** Genuine process recreation: drop the handle, rebuild every layer
         *  from the SAME on-disk shard. No in-memory state carries over. */
        fun recreateProcess() {
            runBlocking { store.close() }
            open()
        }

        fun runtime(): QdrantSyncRuntime =
            QdrantSyncRuntime(store, engine, conflicts, DIMENSION)

        fun record(
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

        fun enqueuePending(record: Record): SyncOperationRecord = runBlocking {
            store.upsert(record)
            val outcome = engine.enqueueIfChanged(store.get(record.id)!!)
            (outcome as ChangeDetectionOutcome.Enqueued).operation
        }

        fun cloudItem(
            id: String = UUID.randomUUID().toString(),
            version: Int = 3,
            content: String = "Cloud torque schedule",
        ): CloudKnowledgeItem = CloudKnowledgeItem(
            memoryId = id,
            subjectKey = "torque/schedule",
            title = "Torque schedule",
            content = content,
            contentHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            version = version,
            updatedAt = currentTime,
        )
    }

    private val context: Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    private fun buildWorker(runtime: QdrantSyncRuntime?): QdrantSyncWorker {
        val factory = object : androidx.work.WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: androidx.work.WorkerParameters,
            ): ListenableWorker? =
                if (workerClassName == QdrantSyncWorker::class.java.name) {
                    QdrantSyncWorker(appContext, workerParameters, runtime)
                } else {
                    null
                }
        }
        return TestListenableWorkerBuilder<QdrantSyncWorker>(context)
            .setWorkerFactory(factory)
            .build()
    }

    private fun runWorker(runtime: QdrantSyncRuntime?): ListenableWorker.Result =
        runBlocking { buildWorker(runtime).doWork() }

    // ------------------------------------------------------------------
    // Orchestration contract
    // ------------------------------------------------------------------

    @Test
    fun workerOrchestratesReconcileThenPullThenPush() = runBlocking {
        val f = Fixture()
        val order = mutableListOf<String>()
        val recording = object : QdrantSyncEngine {
            override suspend fun enqueueIfChanged(record: Record) =
                throw UnsupportedOperationException("not part of the worker cycle")

            override suspend fun pushPending(maxOperations: Int): SyncRunSummary {
                order += "push"
                return f.engine.pushPending(maxOperations)
            }

            override suspend fun pullAndApply(pageSize: Int): SyncPullSummary {
                order += "pull"
                return f.engine.pullAndApply(pageSize)
            }

            override suspend fun reconcile(): ReconciliationSummary {
                order += "reconcile"
                return f.engine.reconcile()
            }
        }
        val pending = f.enqueuePending(f.record())
        val result = runWorker(
            QdrantSyncRuntime(f.store, recording, f.conflicts, DIMENSION),
        )
        assertEquals(listOf("reconcile", "pull", "push"), order)
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(
            OutboxOperationState.ACKED,
            f.operations.findByOperationId(pending.operationId)!!.state,
        )
        f.store.close(); Unit
    }

    // ------------------------------------------------------------------
    // Success path over durable Qdrant state
    // ------------------------------------------------------------------

    @Test
    fun workerDrainsLocalPushAndCloudPullToSuccess() = runBlocking {
        val f = Fixture()
        val pending = f.enqueuePending(f.record())
        val item = f.cloudItem()
        f.pull.batches.add(CloudKnowledgeBatch(listOf(item), nextCursor = null))

        val result = runWorker(f.runtime())

        assertEquals(ListenableWorker.Result.success(), result)
        // Push really happened — real HTTP, real ACK, watermark on the record.
        assertEquals(OutboxOperationState.ACKED, f.operations.findByOperationId(pending.operationId)!!.state)
        assertEquals(listOf(pending.operationId.value), f.cloud.deliveredOperationIds.map { it })
        val synced = f.store.get(pending.recordId)!!
        assertEquals(SyncState.SYNCED, synced.syncState)
        assertEquals(1, synced.lastSyncedVersion)
        // Pull really happened — cloud record durable in the same shard.
        val applied = f.store.get(RecordId.fromString(item.memoryId))
        assertTrue("cloud item must be durable in Qdrant", applied != null && !applied.tombstone)
        assertEquals(listOf<String?>(null), f.pull.cursorsSeen)
    }

    @Test
    fun repeatedWorkerRunsAreIdempotent() = runBlocking {
        val f = Fixture()
        f.enqueuePending(f.record())

        assertEquals(ListenableWorker.Result.success(), runWorker(f.runtime()))
        assertEquals(ListenableWorker.Result.success(), runWorker(f.runtime()))

        // One delivery total: the ACKed identity never re-travels.
        assertEquals(1, f.cloud.attemptedOperationIds.size)
        assertEquals(1, f.cloud.deliveredOperationIds.size)
        assertEquals(1L, f.operations.countByState(OutboxOperationState.ACKED))
    }

    // ------------------------------------------------------------------
    // Retry / failure semantics
    // ------------------------------------------------------------------

    @Test
    fun transientFailureRetriesWithoutFalseAck() = runBlocking {
        val f = Fixture()
        val pending = f.enqueuePending(f.record())
        f.cloud.failNext.set(1) // one 503 from the protocol fixture

        val result = runWorker(f.runtime())

        assertEquals(ListenableWorker.Result.retry(), result)
        val op = f.operations.findByOperationId(pending.operationId)!!
        assertNotEquals(OutboxOperationState.ACKED, op.state)
        assertEquals(OutboxOperationState.FAILED, op.state)
        assertEquals(0, f.cloud.deliveredOperationIds.size)
        // Retry recovers the SAME durable operation to ACKED.
        assertEquals(ListenableWorker.Result.success(), runWorker(f.runtime()))
        assertEquals(OutboxOperationState.ACKED, f.operations.findByOperationId(pending.operationId)!!.state)
        assertEquals(1, f.cloud.deliveredOperationIds.size)
    }

    @Test
    fun permanentFailureIsDeadAndNeverHoldsTheQueueOpen() = runBlocking {
        val f = Fixture()
        val pending = f.enqueuePending(f.record())
        f.cloud.unauthorized = true

        val result = runWorker(f.runtime())

        assertEquals("DEAD is terminal and does not keep retrying", ListenableWorker.Result.success(), result)
        val op = f.operations.findByOperationId(pending.operationId)!!
        assertEquals(OutboxOperationState.DEAD, op.state)
        assertEquals(0, f.cloud.deliveredOperationIds.size)
        // Second run: DEAD stays DEAD, nothing re-delivered, still success.
        assertEquals(ListenableWorker.Result.success(), runWorker(f.runtime()))
        assertEquals(OutboxOperationState.DEAD, f.operations.findByOperationId(pending.operationId)!!.state)
        assertEquals(1, f.cloud.attemptedOperationIds.size)
    }

    @Test
    fun offlineCloudDoesNotDiscardOperationsOrFakeSuccess() = runBlocking {
        // No backend configured at all: honest SOURCE_UNAVAILABLE remote and an
        // unavailable pull source. The operation must stay durably pending —
        // never ACKED — and the worker must ask for retry, not claim success.
        val f = Fixture(remote = UnimplementedQdrantSyncRemote())
        f.pull.unavailable = true
        val pending = f.enqueuePending(f.record())

        val result = runWorker(f.runtime())

        assertEquals(ListenableWorker.Result.retry(), result)
        val op = f.operations.findByOperationId(pending.operationId)!!
        assertNotEquals(OutboxOperationState.ACKED, op.state)
        assertTrue(op.state == OutboxOperationState.FAILED || op.state == OutboxOperationState.PENDING)
    }

    @Test
    fun emptyQueueSucceedsEvenWhenCloudIsUnavailable() = runBlocking {
        val f = Fixture(remote = UnimplementedQdrantSyncRemote())
        f.pull.unavailable = true
        assertEquals(ListenableWorker.Result.success(), runWorker(f.runtime()))
    }

    // ------------------------------------------------------------------
    // Process death / durability
    // ------------------------------------------------------------------

    @Test
    fun workerAfterProcessRecreationRunsFromDurableQdrantState() = runBlocking {
        val f = Fixture()
        val pending = f.enqueuePending(f.record())
        f.cloud.failNext.set(1)
        assertEquals(ListenableWorker.Result.retry(), runWorker(f.runtime()))

        // Hard process death: handle dropped, everything rebuilt from disk.
        f.recreateProcess()

        assertEquals(ListenableWorker.Result.success(), runWorker(f.runtime()))
        val acked = f.operations.findByOperationId(pending.operationId)!!
        assertEquals(OutboxOperationState.ACKED, acked.state)
        // Retried with the SAME deterministic identity — cloud saw it twice,
        // applied it once (idempotency), and never a fabricated first ACK.
        assertEquals(listOf(pending.operationId.value, pending.operationId.value), f.cloud.attemptedOperationIds)
        assertEquals(1, f.cloud.points.size)
    }

    @Test
    fun reconciliationRunsInsideTheWorkerTombstoneIsPropagated() = runBlocking {
        val f = Fixture()
        val original = f.record()
        val pending = f.enqueuePending(original)
        assertEquals(ListenableWorker.Result.success(), runWorker(f.runtime()))
        assertEquals(OutboxOperationState.ACKED, f.operations.findByOperationId(pending.operationId)!!.state)

        // Crash boundary: tombstone written, process died before detection.
        f.store.softDelete(original.id)
        assertEquals(
            "no tombstone operation was detected before the worker run",
            0L,
            operationWithType(f, SyncOperationType.TOMBSTONE),
        )

        // The worker's reconcile pass (R4) must enqueue + deliver the
        // TOMBSTONE operation without any caller explicitly detecting it.
        assertEquals(ListenableWorker.Result.success(), runWorker(f.runtime()))

        val cloudPoint = f.cloud.points[original.id.uuid]!!
        assertTrue("cloud must hold the tombstone after the worker run", cloudPoint.tombstone)
        val tombstoneOp = allOperations(f).first { it.operationType == SyncOperationType.TOMBSTONE }
        assertEquals(OutboxOperationState.ACKED, tombstoneOp.state)
    }

    @Test
    fun pendingRecordWithoutOperationIsRepairedByTheWorker() = runBlocking {
        val f = Fixture()
        // Crash boundary §18 case R2: record flushed as PENDING, process died
        // before its operation was persisted.
        val record = f.record()
        f.store.upsert(record.copy(syncState = SyncState.PENDING))

        assertEquals(ListenableWorker.Result.success(), runWorker(f.runtime()))

        // R2 re-enqueued through the detector, push delivered, cloud has it.
        assertEquals(1, f.cloud.deliveredOperationIds.size)
        val synced = f.store.get(record.id)!!
        assertEquals(SyncState.SYNCED, synced.syncState)
    }

    // ------------------------------------------------------------------
    // Honest failure when the pipeline cannot be constructed
    // ------------------------------------------------------------------

    @Config(sdk = [35], application = android.app.Application::class)
    @Test
    fun workerFailsHonestlyWhenApplicationProvidesNoContainer() {
        // A non-EdgeMindApplication host has no container to resolve.
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val worker = TestListenableWorkerBuilder<QdrantSyncWorker>(context).build()
        assertEquals(ListenableWorker.Result.failure(), runBlocking { worker.doWork() })
    }

    @Test
    fun workerResolvesProductionRuntimeFromApplicationContainer() {
        // No injected runtime: the worker must reach the real production
        // dependency graph through EdgeMindApplication — exactly the path a
        // WorkManager-scheduled execution takes after process recreation.
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<com.example.EdgeMemo.EdgeMindApplication>()
        val worker = TestListenableWorkerBuilder<QdrantSyncWorker>(app).build()
        // Fresh Robolectric-managed EdgeMindApplication with an empty queue:
        // honest success, and the container runtime really is what ran.
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
        assertTrue(app.container.qdrantSyncRuntime.engine is DefaultQdrantSyncEngine)
    }

    @Test
    fun localOnlyRecordNeverReachesTheCloudThroughTheWorker() = runBlocking {
        val f = Fixture()
        val private = f.record(decision = SyncDecision.LOCAL_ONLY)
        f.store.upsert(private)
        val outcome = f.engine.enqueueIfChanged(f.store.get(private.id)!!)
        assertTrue(
            "LOCAL_ONLY must be policy-refused, got $outcome",
            outcome is ChangeDetectionOutcome.LocalOnlyProtected,
        )

        assertEquals(ListenableWorker.Result.success(), runWorker(f.runtime()))
        assertEquals(0, f.cloud.attemptedOperationIds.size)
        assertEquals(0, f.cloud.deliveredOperationIds.size)
    }

    // ------------------------------------------------------------------
    // store helpers (indexed listing over the REAL operation store)
    // ------------------------------------------------------------------

    private fun allOperations(f: Fixture): List<SyncOperationRecord> = runBlocking {
        f.operations.listByStates(OutboxOperationState.entries.toSet(), 1_000, null).operations
    }

    private fun operationWithType(f: Fixture, type: SyncOperationType): Long =
        allOperations(f).count { it.operationType == type }.toLong()
}
