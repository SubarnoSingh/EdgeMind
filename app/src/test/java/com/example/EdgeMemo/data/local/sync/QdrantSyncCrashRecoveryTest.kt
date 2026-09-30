package com.example.EdgeMemo.data.local.sync

import com.example.EdgeMemo.core.record.JsonString
import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.LocalRecordStore
import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordOrigin
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.record.SyncDecision
import com.example.EdgeMemo.core.record.SyncState
import com.example.EdgeMemo.core.sync.CanonicalContentHash
import com.example.EdgeMemo.core.sync.ChangeDetectionOutcome
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.core.sync.SyncFailureKind
import com.example.EdgeMemo.core.sync.SyncOperationId
import com.example.EdgeMemo.core.sync.SyncOperationRecord
import com.example.EdgeMemo.core.sync.SyncOperationType
import com.example.EdgeMemo.core.sync.SyncPushOutcome
import com.example.EdgeMemo.data.remote.CloudHttpClient
import com.example.EdgeMemo.data.sync.HttpQdrantSyncRemote
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeBatch
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource
import com.example.EdgeMemo.native.qdrant.NativeBridge
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import java.util.concurrent.TimeUnit
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
 * Phase 12B.11 — crash recovery of the Qdrant-native sync state.
 *
 * Recovery is tested against the REAL shard (JNI → qdrant-edge) and the real
 * HTTP protocol fixture. Where a requirement says "process death", the test
 * either closes and reopens the store in-process (durable-state equivalence:
 * QdrantEdgeRecordStore flushes after every write) or uses a genuine child
 * JVM that exits WITHOUT closing the shard, exactly like the VERIFIED
 * Phase-10 methodology.
 *
 * No WorkManager, no scheduler: reconciliation is invoked explicitly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QdrantSyncCrashRecoveryTest {

    companion object {
        private const val DIMENSION = 4
        private const val LIB_PROPERTY = "edgememo.qdrant.lib"
        private const val PHASE_PROPERTY = "edge.ph"
        private const val PERSIST_DIR_PROPERTY = "edge.persist.dir"
        private const val CRASH_RECORD_ID = "00000000-0000-0000-0000-000000000c1a"

        /** Plain-JVM safe knowledge-record envelope, sync_state PENDING, no op. */
        private const val CRASH_RECORD_PAYLOAD =
            "{" +
                "\"_record_type\":\"procedure\"," +
                "\"_version\":1," +
                "\"_created_at\":1700000000000," +
                "\"_updated_at\":1700000000000," +
                "\"_source\":\"USER_ENTRY\"," +
                "\"_sync_decision\":\"SYNC\"," +
                "\"_sync_state\":\"PENDING\"," +
                "\"_tombstone\":false," +
                "\"_origin\":\"LOCAL\"," +
                "\"title\":\"Crash-window procedure\"," +
                "\"content\":\"Rebuild motor housing\"" +
                "}"

        @JvmStatic
        fun main(args: Array<String>) {
            val phase = args.firstOrNull() ?: System.getProperty(PHASE_PROPERTY)
                ?: throw IllegalArgumentException("phase required")
            val persistDir = File(
                System.getProperty(PERSIST_DIR_PROPERTY)
                    ?: throw IllegalArgumentException("$PERSIST_DIR_PROPERTY required"),
            )
            System.load(System.getProperty(LIB_PROPERTY))
            when (phase) {
                // Process death simulation: write a PENDING knowledge record,
                // flush, exit WITHOUT nativeClose — no graceful drop.
                "reconcileCrashWrite" -> {
                    persistDir.mkdirs()
                    val handle = NativeBridge.nativeCreate(persistDir.absolutePath, DIMENSION)
                    NativeBridge.nativeUpsertPayloadOnly(handle, CRASH_RECORD_ID, CRASH_RECORD_PAYLOAD)
                    NativeBridge.nativeFlush(handle)
                }
                else -> throw IllegalArgumentException("unknown phase: $phase")
            }
        }

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    private var currentTime = 1_700_000_000_000L

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun tempDir(): File {
        val d = File.createTempFile("edgememo-12b11-", "")
        d.delete(); d.mkdirs(); return d
    }

    private class Partitions(
        val dir: File,
        val store: com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore,
        val operations: QdrantSyncOperationStore,
        val detector: QdrantChangeDetector,
        val reconciler: QdrantSyncReconciler,
    )

    private fun fresh(dir: File): Partitions {
        val store = com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(dir)
        runBlocking {
            store.ensureReady(DIMENSION)
            store.ensureIndexes()
        }
        return partitions(dir, store)
    }

    private fun reopened(dir: File): Partitions {
        val store = com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(dir)
        runBlocking { store.open() }
        return partitions(dir, store)
    }

    private fun partitions(
        dir: File,
        store: com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore,
    ): Partitions {
        val operations = QdrantSyncOperationStore(store) { currentTime }
        val detector = QdrantChangeDetector(store, operations) { currentTime }
        val reconciler = QdrantSyncReconciler(store, operations, detector) { currentTime }
        return Partitions(dir, store, operations, detector, reconciler)
    }

    // ------------------------------------------------------------------
    // R1–R5 reconciliation
    // ------------------------------------------------------------------

    @Test
    fun pendingRecordWithoutOperationIsReEnqueuedExactlyOnce() = runBlocking {
        val dir = tempDir()
        val p = fresh(dir)
        try {
            val r = knowledgeRecord(content = "unsent change")
            (p.store).upsert(r.copy(syncState = SyncState.PENDING))

            val first = p.reconciler.reconcile(currentTime)
            assertEquals(1, first.missingOperationsEnqueued)
            val opId = SyncOperationId.generate(SyncOperationType.UPSERT, r.id, 1)
            assertNotNull(p.operations.findByOperationId(opId))

            // Idempotent second pass: nothing new.
            val second = p.reconciler.reconcile(currentTime)
            assertEquals(0, second.missingOperationsEnqueued)
            assertEquals(1L, p.operations.countByState(OutboxOperationState.PENDING))
        } finally {
            runCatching { p.store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun localOnlyRecordIsNeverReEnqueuedByReconciliation() = runBlocking {
        val dir = tempDir()
        val p = fresh(dir)
        try {
            val r = knowledgeRecord(content = "gate code 4821")
                .copy(syncState = SyncState.PENDING, syncDecision = SyncDecision.LOCAL_ONLY)
            p.store.upsert(r)
            val summary = p.reconciler.reconcile(currentTime)
            assertEquals(0, summary.missingOperationsEnqueued)
            assertEquals(0L, p.operations.countByState(OutboxOperationState.PENDING))
        } finally {
            runCatching { p.store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun staleInFlightLeaseIsRecoveredAndReclaimedUnderSameIdentity() = runBlocking {
        val dir = tempDir()
        val p = fresh(dir)
        try {
            val r = knowledgeRecord()
            p.store.upsert(r)
            val outcome = p.detector.detectAndEnqueue(p.store.get(r.id)!!)
            val op = (outcome as ChangeDetectionOutcome.Enqueued).operation

            p.operations.claimNext(1, 30_000L)
            currentTime += 31_000 // lease expiry (process death mid-delivery)

            val summary = p.reconciler.reconcile(currentTime)
            assertEquals(1, summary.staleInFlightRecovered)

            // Reclaim: same deterministic identity, not a new operation.
            val reclaimed = p.operations.claimNext(1, 30_000L)
            assertEquals(1, reclaimed.size)
            assertEquals(op.operationId, reclaimed.first().operationId)
            assertEquals(OutboxOperationState.IN_FLIGHT, reclaimed.first().state)
            assertEquals(1L, countOperationsFor(p, r.id))
        } finally {
            runCatching { p.store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun acknowledgedOperationWithLostWatermarkIsRepaired() = runBlocking {
        val dir = tempDir()
        val p = fresh(dir)
        try {
            val r = knowledgeRecord()
            p.store.upsert(r)
            val op = (p.detector.detectAndEnqueue(p.store.get(r.id)!!)
                as ChangeDetectionOutcome.Enqueued).operation
            // Cloud ACK persisted, then the process died BEFORE the record
            // watermark write (§18 case 5 / R5).
            p.operations.claimNext(1, 30_000L)
            assertTrue(p.operations.markAcked(op.operationId, cloudVersion = 1))
            assertEquals(SyncState.PENDING, (p.store.get(r.id)!!).syncState)

            val summary = p.reconciler.reconcile(currentTime)
            assertEquals(1, summary.acknowledgementsRepaired)
            val repaired = p.store.get(r.id)!!
            assertEquals(SyncState.SYNCED, repaired.syncState)
            assertEquals(1, repaired.lastSyncedVersion)
            assertEquals(op.operationId.value, repaired.lastSyncedOperationId)
            assertEquals(0, p.reconciler.reconcile(currentTime).acknowledgementsRepaired)
        } finally {
            runCatching { p.store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun orphanedOperationIsReportedNotDestroyed() = runBlocking {
        val dir = tempDir()
        val p = fresh(dir)
        try {
            // An operation for a record that does not exist (R3).
            val orphanId = RecordId.random()
            val opId = SyncOperationId.generate(SyncOperationType.UPSERT, orphanId, 3)
            val synthetic = SyncOperationRecord(
                recordId = orphanId,
                operationId = opId,
                operationType = SyncOperationType.UPSERT,
                state = OutboxOperationState.PENDING,
                attempts = 0,
                lastError = null,
                createdAt = currentTime,
                updatedAt = currentTime,
                leaseUntil = null,
                version = 3,
                syncDecision = SyncDecision.SYNC,
                redacted = false,
                payload = mapOf("content" to JsonValue.fromString("ghost")),
            )
            p.operations.enqueue(synthetic)

            val summary = p.reconciler.reconcile(currentTime)
            assertEquals(1, summary.orphanedOperationsReported)
            // PENDING → DEAD is an ILLEGAL transition (§5.3): reconciliation
            // reports orphans, it does not invent a state machine change.
            assertEquals(
                OutboxOperationState.PENDING,
                p.operations.findByOperationId(opId)!!.state,
            )
        } finally {
            runCatching { p.store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun tombstoneWithoutOperationIsEnqueuedByReconciliation() = runBlocking {
        val dir = tempDir()
        val p = fresh(dir)
        try {
            val r = knowledgeRecord()
            p.store.upsert(r)
            // v1 synced; tombstone v2 flushed, upload never happened (§18 case 9).
            p.store.upsert(r.copy(syncState = SyncState.SYNCED, lastSyncedVersion = 1))
            p.store.upsert(
                r.copy(
                    version = 2,
                    tombstone = true,
                    deletedAt = currentTime,
                    syncState = SyncState.LOCAL,
                ),
            )

            val summary = p.reconciler.reconcile(currentTime)
            assertEquals(1, summary.tombstonePropagationEnqueued)
            val tombstoneOpId = SyncOperationId.generate(SyncOperationType.TOMBSTONE, r.id, 2)
            val op = p.operations.findByOperationId(tombstoneOpId)
            assertNotNull(op)
            assertEquals(OutboxOperationState.PENDING, op!!.state)
        } finally {
            runCatching { p.store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun deadOperationStaysTerminalThroughReconcileAndRestart() = runBlocking {
        val dir = tempDir()
        val p = fresh(dir)
        val r = knowledgeRecord()
        p.store.upsert(r)
        val op = (p.detector.detectAndEnqueue(p.store.get(r.id)!!)
            as ChangeDetectionOutcome.Enqueued).operation
        p.operations.claimNext(1, 30_000L)
        assertTrue(p.operations.markDead(op.operationId, SyncFailureKind.UNAUTHORIZED))
        runCatching { (p.store).close() }

        val q = reopened(dir)
        try {
            currentTime += 60_000
            val summary = q.reconciler.reconcile(currentTime)
            assertEquals(0, summary.staleInFlightRecovered)
            assertEquals(0L, summary.missingOperationsEnqueued.toLong())
            val stillDead = q.operations.findByOperationId(op.operationId)!!
            assertEquals(OutboxOperationState.DEAD, stillDead.state)
            assertTrue(q.operations.claimNext(5, 30_000L).isEmpty())
            // The record stays PENDING (never silently marked synced) — its
            // sync intent remains durable and explainable.
            assertEquals(SyncState.PENDING, (q.store.get(r.id)!!).syncState)
        } finally {
            runCatching { (q.store).close() }
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // cloud ACK crash window (§18 cases 3–4) — real HTTP + protocol fixture
    // ------------------------------------------------------------------

    @Test
    fun cloudAckCrashWindowReDeliversIdenticalOperationAndAcknowledgesOnce() = runBlocking {
        val dir = tempDir()
        val cloud = Phase12CloudFixture()
        val backend = cloud.start()
        val store = com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(dir)
        store.ensureReady(DIMENSION)
        store.ensureIndexes()
        val remote = HttpQdrantSyncRemote(CloudHttpClient(backend.baseUrl))
        try {
            val r = knowledgeRecord()
            store.upsert(r)
            val operations = QdrantSyncOperationStore(store) { currentTime }
            val detector = QdrantChangeDetector(store, operations) { currentTime }
            val engine = DefaultQdrantSyncEngine(
                recordStore = store,
                operationStore = operations,
                detector = detector,
                remote = remote,
                cloudKnowledge = UnreachableKnowledge(),
                clock = { currentTime },
                leaseMs = 30_000L,
            )
            val op = (engine.enqueueIfChanged(r) as ChangeDetectionOutcome.Enqueued).operation

            // 1–2: claim + deliver; cloud accepts.
            operations.claimNext(1, 30_000L)
            val claimed = operations.findByOperationId(op.operationId)!!
            val appliedOutcome = remote.push(claimed)
            assertTrue(appliedOutcome is SyncPushOutcome.Applied)
            // 3: process dies BEFORE the local ACK is persisted.
            store.close()

            // 4: restart, lease expired.
            currentTime += 31_000
            val reopenedStore = com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(dir)
            reopenedStore.open()
            val operations2 = QdrantSyncOperationStore(reopenedStore) { currentTime }
            val engine2 = DefaultQdrantSyncEngine(
                recordStore = reopenedStore,
                operationStore = operations2,
                detector = QdrantChangeDetector(reopenedStore, operations2) { currentTime },
                remote = remote,
                cloudKnowledge = UnreachableKnowledge(),
                clock = { currentTime },
                leaseMs = 30_000L,
            )

            // 5–7: recovered, re-delivered with the IDENTICAL operation id;
            // the cloud answers DUPLICATE (no logical duplication).
            val summary = engine2.pushPending(5)
            assertEquals(1, summary.recovered)
            assertEquals(1, summary.acked)
            assertEquals(
                listOf(op.operationId.value, op.operationId.value),
                cloud.attemptedOperationIds,
            )
            assertEquals(1, cloud.points.size)

            // 8: local state becomes ACKED + watermarked.
            assertEquals(OutboxOperationState.ACKED, operations2.findByOperationId(op.operationId)!!.state)
            val synced = reopenedStore.get(r.id)!!
            assertEquals(SyncState.SYNCED, synced.syncState)
            assertEquals(1, synced.lastSyncedVersion)
            reopenedStore.close()
        } finally {
            backend.close()
            runCatching { store.close() }
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // cloud→local cursor crash window (§18 case 10)
    // ------------------------------------------------------------------

    @Test
    fun cursorCrashWindowReAppliesSamePageIdempotently() = runBlocking {
        val dir = tempDir()
        val real = com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(dir)
        real.ensureReady(DIMENSION)
        real.ensureIndexes()
        val idA = RecordId.random()
        val idB = RecordId.random()
        val batch = CloudKnowledgeBatch(
            items = listOf(cloudItem(idA, version = 1), cloudItem(idB, version = 1)),
            nextCursor = "cursor-2",
        )
        val source = object : CloudKnowledgeRemoteDataSource {
            override suspend fun pullKnowledge(cursor: String?) = batch
        }
        try {
            // Crash injected after the FIRST record write of the page: the
            // page was NOT fully applied and the cursor was NOT advanced.
            val injected = CrashAfterUpserts(real, crashAfter = 1)
            val engine1 = engine(injected, source)
            assertThrowsDeath { engine1.pullAndApply(pageSize = 20) }
            // The crash happened after the first item's upsert and its
            // implicit flush; kill the "process".
            real.close()

            // Restart.
            val reopened = com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(dir)
            reopened.open()
            try {
                val engine2 = engine(reopened, source)
                val summary = engine2.pullAndApply(pageSize = 20)
                // First item now DUPLICATE (idempotent by id+version+hash),
                // second applied. Cursor finally advanced.
                assertEquals(1, summary.duplicates)
                assertEquals(1, summary.applied)
                assertEquals("cursor-2", summary.nextCursor)
                assertNotNull(reopened.get(idA))
                assertNotNull(reopened.get(idB))
                // No echo operations for cloud-applied records.
                val operations = QdrantSyncOperationStore(reopened) { currentTime }
                assertEquals(0L, operations.countByState(OutboxOperationState.PENDING))
                // The cursor is durably stored as a SYS_CURSOR point and was
                // advanced exactly once, at the safe point after re-apply.
                val cursorPages = reopened.scroll(
                    com.example.EdgeMemo.core.record.RecordQuery.Scroll(
                        limit = 5,
                        recordTypes = setOf(RecordType.SYS_CURSOR),
                    ),
                )
                assertEquals(1, cursorPages.records.size)
                assertEquals(
                    "cursor-2",
                    (cursorPages.records.first().payload["cursor"] as JsonString).value,
                )
            } finally {
                reopened.close()
            }
        } finally {
            runCatching { real.close() }
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // conflict crash windows (§18 case 8 + resolution replay)
    // ------------------------------------------------------------------

    @Test
    fun conflictEvidenceSurvivesCrashDuringPullAndStaysResolvable() = runBlocking {
        val dir = tempDir()
        val real = com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(dir)
        real.ensureReady(DIMENSION)
        real.ensureIndexes()
        val local = knowledgeRecord(content = "local truth")
        real.upsert(local)
        val conflictItem = cloudItem(
            recordId = local.id,
            version = 1,
            hash = "b".repeat(64),
            content = "cloud truth",
        )
        try {
            // Conflict recorded (upsert #1), crash before the cursor write.
            val injected = CrashAfterUpserts(real, crashAfter = 1)
            val engine = engine(injected, object : CloudKnowledgeRemoteDataSource {
                override suspend fun pullKnowledge(cursor: String?) =
                    CloudKnowledgeBatch(items = listOf(conflictItem), nextCursor = "next-1")
            })
            assertThrowsDeath { engine.pullAndApply(pageSize = 20) }
            real.close()

            val reopened = com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(dir)
            reopened.open()
            try {
                // The conflict is STILL DETECTABLE after the crash.
                val operations = QdrantSyncOperationStore(reopened) { currentTime }
                val detector = QdrantChangeDetector(reopened, operations) { currentTime }
                val resolver = QdrantConflictResolver(reopened, detector) { currentTime }
                val unresolved = resolver.listUnresolved(10)
                assertEquals(1, unresolved.size)
                val case = QdrantConflictRecorder.caseOf(unresolved.first())
                assertEquals("local truth", case.localContent)
                assertEquals("cloud truth", case.incomingContent)
                assertEquals("UNRESOLVED", case.state)

                // Replaying the page re-records onto the SAME conflict point.
                val engine2 = engine(reopened, object : CloudKnowledgeRemoteDataSource {
                    override suspend fun pullKnowledge(cursor: String?) =
                        CloudKnowledgeBatch(items = listOf(conflictItem), nextCursor = "next-1")
                })
                engine2.pullAndApply(pageSize = 20)
                assertEquals(1, resolver.listUnresolved(10).size)

                // And it can be resolved normally afterwards.
                val result = resolver.resolveLocal(unresolved.first().id, note = "verified on site")
                assertEquals(
                    com.example.EdgeMemo.domain.conflict.ConflictResolutionState.RESOLVED_LOCAL,
                    result.state,
                )
            } finally {
                reopened.close()
            }
        } finally {
            runCatching { real.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun resolutionCrashAfterRecordApplyResumesWithoutDoubleBump() = runBlocking {
        val dir = tempDir()
        val real = com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(dir)
        real.ensureReady(DIMENSION)
        real.ensureIndexes()
        val local = knowledgeRecord(content = "local truth")
        val conflict = runBlocking {
            real.upsert(local)
            val recorder = QdrantConflictRecorder(real) { currentTime }
            recorder.record(
                subject = "SKP-1",
                local = local,
                incoming = ConflictEvidence(
                    recordId = local.id.uuid,
                    version = 1,
                    contentHash = "b".repeat(64),
                    origin = "CLOUD",
                    authority = "registry",
                    title = "Cloud title",
                    content = "cloud truth",
                ),
                reason = "PULL_CONFLICT",
            )
        }
        try {
            // Crash schedule: upsert #1 = intent marker, upsert #2 = applied
            // record; die BEFORE the applied marker and the operation.
            val injected = CrashAfterUpserts(real, crashAfter = 2)
            val operations1 = QdrantSyncOperationStore(injected) { currentTime }
            val detector1 = QdrantChangeDetector(injected, operations1) { currentTime }
            val resolver1 = QdrantConflictResolver(injected, detector1) { currentTime }
            assertThrowsDeath { resolver1.resolveCloud(conflict.id, note = "authority") }
            real.close()

            val reopened = com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(dir)
            reopened.open()
            try {
                val operations = QdrantSyncOperationStore(reopened) { currentTime }
                val detector = QdrantChangeDetector(reopened, operations) { currentTime }
                val resolver = QdrantConflictResolver(reopened, detector) { currentTime }

                // Replay resumes from the FROZEN intent — same version,
                // never a second bump.
                val result = resolver.resolveCloud(conflict.id, note = "authority (replayed)")
                assertEquals(2, result.appliedVersion)
                assertEquals(2, reopened.get(local.id)!!.version)
                assertEquals("cloud truth", (reopened.get(local.id)!!.payload["content"] as JsonString).value)
                assertEquals(
                    com.example.EdgeMemo.domain.conflict.ConflictResolutionState.RESOLVED_CLOUD,
                    result.state,
                )

                // Exactly ONE follow-up operation, deterministic identity.
                val opId = SyncOperationId.generate(SyncOperationType.UPSERT, local.id, 2)
                assertNotNull(operations.findByOperationId(opId))
                assertEquals(1L, operations.countByState(OutboxOperationState.PENDING))

                // No third resolution bump: repeat is a pure no-op.
                val again = resolver.resolveCloud(conflict.id)
                assertTrue(again.alreadyResolved)
                assertEquals(2, reopened.get(local.id)!!.version)
            } finally {
                reopened.close()
            }
        } finally {
            runCatching { real.close() }
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // tombstone safety after restart
    // ------------------------------------------------------------------

    @Test
    fun tombstonedRecordIsNeverResurrectedAcrossRestartAndReplay() = runBlocking {
        val dir = tempDir()
        val real = com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(dir)
        real.ensureReady(DIMENSION)
        real.ensureIndexes()
        val id = RecordId.random()
        runBlocking {
            real.upsert(knowledgeRecord(id = id).copy(syncState = SyncState.SYNCED, lastSyncedVersion = 1))
            real.softDelete(id) // tombstone v2
        }
        val staleItem = cloudItem(id, version = 1, hash = "a".repeat(64), tombstone = false)
        val source = object : CloudKnowledgeRemoteDataSource {
            override suspend fun pullKnowledge(cursor: String?) =
                CloudKnowledgeBatch(items = listOf(staleItem), nextCursor = null)
        }
        try {
            val engine = engine(real, source)
            assertEquals(1, engine.pullAndApply(pageSize = 20).stale)
            real.close()

            val reopened = com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(dir)
            reopened.open()
            try {
                val engine2 = engine(reopened, source)
                assertEquals(1, engine2.pullAndApply(pageSize = 20).stale)
                assertTrue(reopened.get(id)!!.tombstone)
                assertEquals(2, reopened.get(id)!!.version)
            } finally {
                reopened.close()
            }
        } finally {
            runCatching { real.close() }
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // genuine process death (child JVM exits without close) + reconciliation
    // ------------------------------------------------------------------

    @Test
    fun pendingRecordFromCrashedProcessIsReconciledAndPushed() = runBlocking {
        val dir = tempDir()
        val cloud = Phase12CloudFixture()
        val backend = cloud.start()
        try {
            // Child JVM: writes the PENDING record + flush and EXITS without
            // nativeClose — the closest reproducible Android process death.
            val lib = TestNativeLoader.nativeLibraryPath()
            val java = "${System.getProperty("java.home")}/bin/java"
            val process = ProcessBuilder(
                java,
                "-D$LIB_PROPERTY=$lib",
                "-D$PHASE_PROPERTY=reconcileCrashWrite",
                "-D$PERSIST_DIR_PROPERTY=${dir.absolutePath}",
                "-cp",
                System.getProperty("java.class.path"),
                QdrantSyncCrashRecoveryTest::class.java.name,
                "reconcileCrashWrite",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().decodeToString()
            val finished = process.waitFor(120, TimeUnit.SECONDS)
            process.destroyForcibly()
            assertTrue("crash-write process must succeed:\n$output", finished && process.exitValue() == 0)

            // Parent: reopen the shard, reconcile the durable state, push.
            val store = com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(dir)
            store.open()
            store.ensureIndexes()
            val recordId = RecordId.fromString(CRASH_RECORD_ID)
            val operations = QdrantSyncOperationStore(store) { currentTime }
            val detector = QdrantChangeDetector(store, operations) { currentTime }
            val engine = DefaultQdrantSyncEngine(
                recordStore = store,
                operationStore = operations,
                detector = detector,
                remote = HttpQdrantSyncRemote(CloudHttpClient(backend.baseUrl)),
                cloudKnowledge = UnreachableKnowledge(),
                clock = { currentTime },
            )
            try {
                val summary = engine.reconcile()
                assertEquals(1, summary.missingOperationsEnqueued)
                val drained = engine.pushPending(5)
                assertEquals(1, drained.acked)
                assertEquals(SyncState.SYNCED, store.get(recordId)!!.syncState)
                assertEquals(1, store.get(recordId)!!.lastSyncedVersion)
                assertEquals(1, cloud.points.size)
            } finally {
                store.close()
            }
        } finally {
            backend.close()
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // engine contract: reconcile() exists on QdrantSyncEngine and is bounded
    // ------------------------------------------------------------------

    @Test
    fun engineReconcileIsBoundedAndHonest() = runBlocking {
        val dir = tempDir()
        val store = com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(dir)
        store.ensureReady(DIMENSION)
        store.ensureIndexes()
        val operations = QdrantSyncOperationStore(store) { currentTime }
        val engine = DefaultQdrantSyncEngine(
            recordStore = store,
            operationStore = operations,
            detector = QdrantChangeDetector(store, operations) { currentTime },
            remote = HttpQdrantSyncRemote(CloudHttpClient("http://127.0.0.1:1")),
            cloudKnowledge = UnreachableKnowledge(),
            clock = { currentTime },
        )
        try {
            val summary = engine.reconcile()
            assertEquals(0, summary.missingOperationsEnqueued)
            assertEquals(0, summary.staleInFlightRecovered)
            assertEquals(0, summary.acknowledgementsRepaired)
            assertEquals(0, summary.orphanedOperationsReported)
            assertEquals(0L, summary.claimableOperationsRemaining)
            assertTrue(summary.recordsScanned >= 0)
        } finally {
            store.close()
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // fixtures & plumbing
    // ------------------------------------------------------------------

    class SimulatedProcessDeath : RuntimeException("simulated crash")

    private class CrashAfterUpserts(
        private val delegate: LocalRecordStore,
        private val crashAfter: Int,
    ) : LocalRecordStore by delegate {
        private var upserts = 0
        override suspend fun upsert(record: Record): Record {
            if (++upserts > crashAfter) throw SimulatedProcessDeath()
            return delegate.upsert(record)
        }

        override suspend fun upsertBatch(records: List<Record>): List<Record> {
            records.forEach { upsert(it) }
            return records
        }
    }

    private fun engine(
        store: LocalRecordStore,
        source: CloudKnowledgeRemoteDataSource,
    ): DefaultQdrantSyncEngine {
        val operations = QdrantSyncOperationStore(store) { currentTime }
        return DefaultQdrantSyncEngine(
            recordStore = store,
            operationStore = operations,
            detector = QdrantChangeDetector(store, operations) { currentTime },
            remote = object : com.example.EdgeMemo.core.sync.QdrantSyncRemote {
                override suspend fun push(operation: SyncOperationRecord) =
                    throw AssertionError("crash-window pull tests must never push")
            },
            cloudKnowledge = source,
            clock = { currentTime },
        )
    }

    private fun assertThrowsDeath(block: suspend () -> Unit) {
        val error = runCatching { runBlocking { block() } }.exceptionOrNull()
        var cause: Throwable? = error
        var found = false
        while (cause != null) {
            if (cause is SimulatedProcessDeath) { found = true; break }
            cause = cause.cause
        }
        assertTrue("expected SimulatedProcessDeath, got $error", found)
    }

    private fun knowledgeRecord(
        id: RecordId = RecordId.random(),
        content: String = "Replace the seal",
        version: Int = 1,
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
        syncDecision = SyncDecision.SYNC,
        contentHash = CanonicalContentHash.hash(
            mapOf(
                "title" to JsonValue.fromString("Pump seal procedure"),
                "content" to JsonValue.fromString(content),
            ),
        ),
    )

    private fun cloudItem(
        recordId: RecordId,
        version: Int,
        hash: String = "a".repeat(64),
        content: String = "Cloud content",
        tombstone: Boolean = false,
    ): CloudKnowledgeItem = CloudKnowledgeItem(
        memoryId = recordId.uuid,
        subjectKey = null,
        title = "Cloud title",
        content = content,
        contentHash = hash,
        version = version,
        updatedAt = currentTime,
        origin = "CLOUD",
        tombstone = tombstone,
    )

    private class UnreachableKnowledge : CloudKnowledgeRemoteDataSource {
        override suspend fun pullKnowledge(cursor: String?): CloudKnowledgeBatch =
            throw com.example.EdgeMemo.core.common.EdgeError.CloudUnavailable("not used")
    }

    private suspend fun countOperationsFor(p: Partitions, recordId: RecordId): Long {
        var total = 0L
        var offset: String? = null
        while (true) {
            val page = p.operations.listByStates(
                setOf(
                    OutboxOperationState.PENDING,
                    OutboxOperationState.IN_FLIGHT,
                    OutboxOperationState.FAILED,
                    OutboxOperationState.ACKED,
                    OutboxOperationState.DEAD,
                ),
                limit = 50,
                offsetId = offset,
            )
            total += page.operations.count { it.recordId == recordId }.toLong()
            if (page.nextOffsetId == null || page.operations.isEmpty()) break
            offset = page.nextOffsetId
        }
        return total
    }
}
