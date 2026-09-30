package com.example.EdgeMemo.data.local.sync

import com.example.EdgeMemo.core.record.JsonString
import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.record.SyncDecision
import com.example.EdgeMemo.core.record.SyncState
import com.example.EdgeMemo.core.sync.CanonicalContentHash
import com.example.EdgeMemo.core.sync.ChangeDetectionOutcome
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.core.sync.SyncOperationId
import com.example.EdgeMemo.core.sync.SyncOperationType
import com.example.EdgeMemo.domain.conflict.ConflictResolutionState
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 12B.10 — conflict resolution over the durable evidence recorded by
 * 12B.9. All state lives in the REAL qdrant-edge shard via the production
 * JNI path; every assertion re-reads from disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QdrantConflictResolverTest {

    companion object {
        private const val DIMENSION = 4
        private const val HASH_B = "b"

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    private var currentTime = 1_700_000_000_000L

    private inner class Harness(val dir: File) {
        val store = QdrantEdgeRecordStoreOf(dir)
        val operations = QdrantSyncOperationStore(store) { currentTime }
        val detector = QdrantChangeDetector(store, operations) { currentTime }
        val recorder = QdrantConflictRecorder(store) { currentTime }
        val resolver = QdrantConflictResolver(store, detector) { currentTime }

        init {
            runBlocking {
                store.ensureReady(DIMENSION)
                store.ensureIndexes()
            }
        }

        fun closeAll() {
            runBlocking { runCatching { store.close() } }
            dir.deleteRecursively()
        }
    }

    // small factories ---------------------------------------------------

    private fun localRecord(
        id: RecordId,
        version: Int,
        content: String,
        authority: String? = null,
        tombstone: Boolean = false,
        decision: SyncDecision = SyncDecision.SYNC,
    ): Record = Record(
        id = id,
        recordType = RecordType.PROCEDURE,
        entityId = null,
        vector = floatArrayOf(1f, 0f, 0f, 0f),
        payload = mapOf(
            "title" to JsonValue.fromString("Pump seal procedure"),
            "content" to JsonValue.fromString(content),
        ),
        version = version,
        createdAt = currentTime,
        updatedAt = currentTime,
        syncDecision = decision,
        syncState = if (tombstone) SyncState.LOCAL else SyncState.LOCAL,
        contentHash = CanonicalContentHash.hash(
            mapOf(
                "title" to JsonValue.fromString("Pump seal procedure"),
                "content" to JsonValue.fromString(content),
            ),
        ),
        tombstone = tombstone,
        authority = authority,
    )

    private fun evidence(
        recordId: RecordId,
        version: Int,
        content: String?,
        hash: String,
        authority: String? = null,
        tombstone: Boolean = false,
    ): ConflictEvidence = ConflictEvidence(
        recordId = recordId.uuid,
        version = version,
        contentHash = hash,
        origin = "CLOUD",
        authority = authority,
        title = if (tombstone) null else "Cloud title",
        content = if (tombstone) null else content,
        tombstone = tombstone,
    )

    private fun recordAndConflict(
        h: Harness,
        localVersion: Int = 1,
        localContent: String = "local body",
        localAuthority: String? = null,
        localTombstone: Boolean = false,
        incomingVersion: Int = 1,
        incomingContent: String? = "cloud body",
        incomingHash: String = HASH_B.repeat(64),
        incomingAuthority: String? = null,
        incomingTombstone: Boolean = false,
    ): Pair<RecordId, RecordId> = runBlocking {
        val id = RecordId.random()
        val local = localRecord(id, localVersion, localContent, localAuthority, localTombstone)
        h.store.upsert(local)
        val conflict = h.recorder.record(
            subject = "SKP-1",
            local = local,
            incoming = evidence(id, incomingVersion, incomingContent, incomingHash, incomingAuthority, incomingTombstone),
            reason = "PULL_CONFLICT",
        )
        id to conflict.id
    }

    // ------------------------------------------------------------------
    // automatic authority priority (12A §16)
    // ------------------------------------------------------------------

    @Test
    fun cloudAuthorityWinsAndProducesFollowUpOperation() = runBlocking {
        val h = Harness(tempDir())
        try {
            val (recordId, conflictId) = recordAndConflict(
                h,
                localContent = "superseded local",
                incomingContent = "authoritative cloud",
                incomingAuthority = "maintenance-registry",
            )
            val results = h.resolver.resolveByAuthority(limit = 10)
            assertEquals(1, results.size)
            val resolution = results.first()
            assertEquals(ConflictResolutionState.RESOLVED_CLOUD, resolution.state)
            assertEquals(2, resolution.appliedVersion)

            // Applied state re-read from the shard.
            val applied = h.store.get(recordId)!!
            assertEquals(2, applied.version)
            assertEquals(
                "authoritative cloud",
                (applied.payload["content"] as JsonString).value,
            )
            assertEquals("maintenance-registry", applied.authority)
            assertEquals("CONFLICT_RESOLUTION", applied.source)
            assertEquals(SyncState.PENDING, applied.syncState)
            // Watermark deliberately NOT advanced: the cloud has not ACKed yet.
            assertNull(applied.lastSyncedVersion)

            // Deterministic follow-up operation identity.
            val opId = SyncOperationId.generate(SyncOperationType.UPSERT, recordId, 2)
            assertEquals(opId.value, resolution.followUpOperationId!!.value)
            val op = h.operations.findByOperationId(opId)
            assertNotNull(op)
            assertEquals(OutboxOperationState.PENDING, op!!.state)

            // Conflict evidence preserved and outcome recorded on the SAME point.
            val resolvedConflict = h.store.get(conflictId)!!
            val case = QdrantConflictRecorder.caseOf(resolvedConflict)
            assertEquals("RESOLVED_CLOUD", case.state)
            assertEquals("authoritative cloud", case.incomingContent)
            assertEquals("superseded local", case.localContent)
            assertEquals(2, case.resolutionVersion)
            assertEquals(2, case.appliedVersion)
            assertNotNull(case.resolvedAt)
            assertNotNull(case.resolution)
        } finally {
            h.closeAll()
        }
    }

    @Test
    fun noAuthorityDivergenceStaysUnresolved() = runBlocking {
        val h = Harness(tempDir())
        try {
            recordAndConflict(h, incomingAuthority = null)
            val results = h.resolver.resolveByAuthority(limit = 10)
            assertTrue("no auto winner without authority", results.isEmpty())
            // The record is untouched; the conflict remains UNRESOLVED durable.
            assertEquals(1L, h.resolver.listUnresolved(10).size.toLong())
        } finally {
            h.closeAll()
        }
    }

    @Test
    fun competingAuthoritiesStayUnresolved() = runBlocking {
        val h = Harness(tempDir())
        try {
            recordAndConflict(
                h,
                localAuthority = "site-manual",
                incomingAuthority = "vendor-manual",
            )
            assertTrue(h.resolver.resolveByAuthority(limit = 10).isEmpty())
        } finally {
            h.closeAll()
        }
    }

    @Test
    fun automaticPassNeverResurrectsTombstones() = runBlocking {
        val h = Harness(tempDir())
        try {
            // Local record is tombstoned; cloud (authoritative) offers ACTIVE
            // content at the same version. §23.1 row 8: STALE / explicit-only.
            recordAndConflict(
                h,
                localTombstone = true,
                incomingContent = "attempted resurrection",
                incomingAuthority = "maintenance-registry",
            )
            val results = h.resolver.resolveByAuthority(limit = 10)
            assertTrue(results.isEmpty())
            assertEquals(1L, h.resolver.listUnresolved(10).size.toLong())
        } finally {
            h.closeAll()
        }
    }

    // ------------------------------------------------------------------
    // explicit choices
    // ------------------------------------------------------------------

    @Test
    fun keepLocalLeavesRecordUntouchedAndFreezesEvidence() = runBlocking {
        val h = Harness(tempDir())
        try {
            val (recordId, conflictId) = recordAndConflict(h)
            val before = h.store.get(recordId)!!
            val result = h.resolver.resolveLocal(conflictId, note = "site crew verified local")
            assertEquals(ConflictResolutionState.RESOLVED_LOCAL, result.state)
            assertNull(result.appliedVersion)
            assertNull(result.followUpOperationId)

            val after = h.store.get(recordId)!!
            assertEquals(before.version, after.version)
            assertEquals(before.contentHash, after.contentHash)

            val case = QdrantConflictRecorder.caseOf(h.store.get(conflictId)!!)
            assertEquals("RESOLVED_LOCAL", case.state)
            assertEquals("site crew verified local", case.resolution)
            assertNotNull(case.resolvedAt)
            // Zero operations enqueued by keep-local.
            assertEquals(0L, h.operations.countByState(OutboxOperationState.PENDING))
        } finally {
            h.closeAll()
        }
    }

    @Test
    fun equalVersionDivergentContentResolvesToMaxPlusOne() = runBlocking {
        val h = Harness(tempDir())
        try {
            val (recordId, conflictId) = recordAndConflict(
                h,
                localVersion = 3,
                incomingVersion = 3,
            )
            val result = h.resolver.resolveCloud(conflictId)
            assertEquals(4, result.appliedVersion)
            assertEquals(4, h.store.get(recordId)!!.version)
            // The cloud point's stored hash at v4 matches the incoming evidence
            // hash, so a future same-content pull classifies as DUPLICATE, not
            // CONFLICT → no endless cycle.
            val expectedOp = SyncOperationId.generate(SyncOperationType.UPSERT, recordId, 4)
            assertEquals(expectedOp.value, result.followUpOperationId!!.value)
        } finally {
            h.closeAll()
        }
    }

    @Test
    fun differentVersionConflictResolvesToMaxPlusOne() = runBlocking {
        val h = Harness(tempDir())
        try {
            val (recordId, conflictId) = recordAndConflict(
                h,
                localVersion = 2,
                incomingVersion = 5,
            )
            val result = h.resolver.resolveCloud(conflictId)
            assertEquals(6, result.appliedVersion)
            assertEquals(6, h.store.get(recordId)!!.version)
        } finally {
            h.closeAll()
        }
    }

    @Test
    fun keepLocalWhenNewerLocalWriteSupersededConflict() = runBlocking {
        val h = Harness(tempDir())
        try {
            val (recordId, conflictId) = recordAndConflict(
                h,
                localVersion = 1,
                incomingVersion = 1,
            )
            // A newer local write appears AFTER the conflict was recorded.
            val v5 = localRecord(recordId, version = 5, content = "way newer local")
            h.store.upsert(v5.copy(lastSyncedVersion = null))

            val result = h.resolver.resolveCloud(conflictId, note = "reviewed")
            // Monotonicity: older frozen cloud content is NOT applied over v5.
            assertEquals(5, h.store.get(recordId)!!.version)
            assertEquals("way newer local", (h.store.get(recordId)!!.payload["content"] as JsonString).value)
            assertTrue(result.case.resolution!!.contains("superseded by newer local v5"))
            // Still durably resolved.
            assertEquals("RESOLVED_CLOUD", QdrantConflictRecorder.caseOf(h.store.get(conflictId)!!).state)
        } finally {
            h.closeAll()
        }
    }

    @Test
    fun keepCloudWithoutIncomingContentEvidenceFailsHonestly() = runBlocking<Unit> {
        val h = Harness(tempDir())
        try {
            // PUSH_CONFLICT evidence carries no cloud content at all.
            val id = RecordId.random()
            val local = localRecord(id, 1, "local body")
            h.store.upsert(local)
            val conflict = h.recorder.record(
                subject = "SKP-1",
                local = local,
                incoming = ConflictEvidence(
                    recordId = id.uuid,
                    version = 1,
                    contentHash = "c".repeat(64),
                    origin = "CLOUD",
                    authority = null,
                    title = null,
                    content = null,
                    tombstone = false,
                ),
                reason = "PUSH_CONFLICT",
            )
            // keep-cloud cannot apply content the cloud never sent: honest
            // refusal, never a fabricated overwrite.
            assertThrows(IllegalStateException::class.java) {
                runBlocking { h.resolver.resolveCloud(conflict.id) }
            }
            // Refusal must not corrupt the conflict: still UNRESOLVED, evidence
            // intact, and the record untouched.
            assertEquals("UNRESOLVED", QdrantConflictRecorder.caseOf(h.store.get(conflict.id)!!).state)
            assertEquals(1, h.store.get(id)!!.version)
        } finally {
            h.closeAll()
        }
    }

    @Test
    fun dismissKeepsBothSidesActive() = runBlocking {
        val h = Harness(tempDir())
        try {
            val (recordId, conflictId) = recordAndConflict(h)
            val result = h.resolver.dismiss(conflictId)
            assertEquals(ConflictResolutionState.DISMISSED, result.state)
            assertEquals(1, h.store.get(recordId)!!.version) // untouched
            assertEquals("DISMISSED", QdrantConflictRecorder.caseOf(h.store.get(conflictId)!!).state)
            assertEquals(0L, h.operations.countByState(OutboxOperationState.PENDING))
        } finally {
            h.closeAll()
        }
    }

    // ------------------------------------------------------------------
    // tombstone conflicts
    // ------------------------------------------------------------------

    @Test
    fun localLiveVsCloudTombstoneKeepCloudTombstonesLocally() = runBlocking {
        val h = Harness(tempDir())
        try {
            val (recordId, conflictId) = recordAndConflict(
                h,
                localVersion = 2,
                incomingVersion = 2,
                incomingContent = null,
                incomingTombstone = true,
            )
            val result = h.resolver.resolveCloud(conflictId)
            assertEquals(3, result.appliedVersion)
            val applied = h.store.get(recordId)!!
            assertTrue(applied.tombstone)
            assertNotNull(applied.deletedAt)
            // Deterministic TOMBSTONE follow-up identity.
            val opId = SyncOperationId.generate(SyncOperationType.TOMBSTONE, recordId, 3)
            assertEquals(opId.value, result.followUpOperationId!!.value)
            assertEquals(OutboxOperationState.PENDING, h.operations.findByOperationId(opId)!!.state)
        } finally {
            h.closeAll()
        }
    }

    @Test
    fun explicitResurrectionOfLocalTombstoneAllowedAtNewerVersion() = runBlocking {
        val h = Harness(tempDir())
        try {
            val (recordId, conflictId) = recordAndConflict(
                h,
                localVersion = 4,
                localTombstone = true,
                incomingVersion = 4,
                incomingContent = "revived procedure",
            )
            val result = h.resolver.resolveCloud(conflictId, note = "tombstone was erroneous")
            assertEquals(5, result.appliedVersion)
            val revived = h.store.get(recordId)!!
            assertTrue(!revived.tombstone)
            assertEquals("revived procedure", (revived.payload["content"] as JsonString).value)
            // A resurrection is a NORMAL newer UPSERT — the cloud accepts it
            // via version ordering (§23.1 row 9: explicit only; this IS the
            // explicit path).
            val opId = SyncOperationId.generate(SyncOperationType.UPSERT, recordId, 5)
            assertEquals(opId.value, result.followUpOperationId!!.value)
        } finally {
            h.closeAll()
        }
    }

    // ------------------------------------------------------------------
    // idempotency, persistence, multiple conflicts
    // ------------------------------------------------------------------

    @Test
    fun repeatedResolutionIsIdempotentAndImmutable() = runBlocking {
        val h = Harness(tempDir())
        try {
            val (_, conflictId) = recordAndConflict(h, incomingAuthority = "registry")
            h.resolver.resolveByAuthority(limit = 10)

            val first = h.store.get(conflictId)!!
            val second = h.resolver.resolveCloud(conflictId)
            assertTrue(second.alreadyResolved)
            assertEquals(first.payload, h.store.get(conflictId)!!.payload)

            // And the automatic pass again resolves nothing new.
            assertTrue(h.resolver.resolveByAuthority(limit = 10).isEmpty())
        } finally {
            h.closeAll()
        }
    }

    @Test
    fun multipleConflictsResolveIndependently() = runBlocking {
        val h = Harness(tempDir())
        try {
            val a = recordAndConflict(h, incomingAuthority = "registry", incomingContent = "alpha")
            val b = recordAndConflict(h, incomingAuthority = null, incomingContent = "beta")
            val resolved = h.resolver.resolveByAuthority(limit = 10)
            assertEquals(1, resolved.size)
            assertEquals(a.second, resolved.first().conflict.id)
            assertEquals(1L, h.resolver.listUnresolved(10).size.toLong())
            assertEquals(b.second, h.resolver.listUnresolved(10).first().id)
        } finally {
            h.closeAll()
        }
    }

    @Test
    fun unresolvedConflictSurvivesRestartAndResolvesLater() = runBlocking {
        val h = Harness(tempDir())
        val (recordId, conflictId) = recordAndConflict(h)
        runBlocking { h.store.close() }

        val reopened = QdrantEdgeRecordStoreOf(h.dir)
        runBlocking { reopened.open() }
        try {
            val operations = QdrantSyncOperationStore(reopened) { currentTime }
            val detector = QdrantChangeDetector(reopened, operations) { currentTime }
            val resolver = QdrantConflictResolver(reopened, detector) { currentTime }
            // Evidence readable after restart, still UNRESOLVED.
            val unresolved = resolver.listUnresolved(10)
            assertEquals(1, unresolved.size)
            assertEquals(conflictId, unresolved.first().id)
            val result = resolver.resolveLocal(conflictId)
            assertEquals(ConflictResolutionState.RESOLVED_LOCAL, result.state)
            assertEquals(recordId, result.case.localRecordId)
        } finally {
            runBlocking { reopened.close() }
            h.dir.deleteRecursively()
        }
    }

    @Test
    fun resolvedConflictSurvivesRestartImmutable() = runBlocking {
        val h = Harness(tempDir())
        val (_, conflictId) = recordAndConflict(h, incomingAuthority = "registry")
        h.resolver.resolveByAuthority(limit = 10)
        val before = h.store.get(conflictId)!!
        runBlocking { h.store.close() }

        val reopened = QdrantEdgeRecordStoreOf(h.dir)
        runBlocking { reopened.open() }
        try {
            val operations = QdrantSyncOperationStore(reopened) { currentTime }
            val detector = QdrantChangeDetector(reopened, operations) { currentTime }
            val resolver = QdrantConflictResolver(reopened, detector) { currentTime }
            val after = reopened.get(conflictId)!!
            assertEquals("RESOLVED_CLOUD", QdrantConflictRecorder.caseOf(after).state)
            val again = resolver.resolveLocal(conflictId)
            assertTrue(again.alreadyResolved)
            assertEquals(before.payload["state"], reopened.get(conflictId)!!.payload["state"])
            // No new operations created post-restart.
            assertEquals(1L, operations.countByState(OutboxOperationState.PENDING))
        } finally {
            runBlocking { reopened.close() }
            h.dir.deleteRecursively()
        }
    }

    @Test
    fun resolutionFollowUpConvergesWithoutEchoLoop() = runBlocking {
        val h = Harness(tempDir())
        try {
            val (recordId, conflictId) = recordAndConflict(h, incomingAuthority = "registry")
            val resolution = h.resolver.resolveByAuthority(limit = 10).first()
            val opId = resolution.followUpOperationId!!

            // Drain the follow-up operation to the (ACKED) watermark state
            // manually — mirrors what pushPending does with a real cloud.
            h.operations.claimNext(1, 30_000)
            h.operations.markAcked(opId, cloudVersion = resolution.appliedVersion!!)
            val applied = h.store.get(recordId)!!
            // After ACK, the engine-side watermark rule (identical hash →
            // DUPLICATE classification) means re-detection enqueues nothing.
            val synced = applied.copy(
                syncState = SyncState.SYNCED,
                lastSyncedVersion = applied.version,
                lastSyncedContentHash = CanonicalContentHash.hash(applied.payload),
                lastSyncedOperationId = opId.value,
            )
            h.store.upsert(synced)
            val outcome = h.detector.detectAndEnqueue(h.store.get(recordId)!!)
            assertTrue(outcome is ChangeDetectionOutcome.NoChange)
            assertEquals(
                com.example.EdgeMemo.core.sync.SyncClassification.DUPLICATE,
                (outcome as ChangeDetectionOutcome.NoChange).classification,
            )

            // Re-running the resolution is a no-op; the conflict stays resolved.
            val repeat = h.resolver.resolveCloud(conflictId)
            assertTrue(repeat.alreadyResolved)
            assertEquals(OutboxOperationState.ACKED, h.operations.findByOperationId(opId)!!.state)
        } finally {
            h.closeAll()
        }
    }

    private fun tempDir(): File {
        val d = File.createTempFile("edgememo-12b10-", "")
        d.delete(); d.mkdirs(); return d
    }

    /** Local type alias so the file reads naturally; backed by the production store. */
    private fun QdrantEdgeRecordStoreOf(dir: File): QdrantEdgeRecordStoreHandle =
        QdrantEdgeRecordStoreHandle(com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(dir))

    private class QdrantEdgeRecordStoreHandle(
        delegate: com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore,
    ) : com.example.EdgeMemo.core.record.LocalRecordStore by delegate
}
