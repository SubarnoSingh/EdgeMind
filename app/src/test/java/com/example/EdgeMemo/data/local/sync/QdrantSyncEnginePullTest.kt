package com.example.EdgeMemo.data.local.sync

import com.example.EdgeMemo.core.record.JsonString
import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordQuery
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.record.SyncState
import com.example.EdgeMemo.core.sync.ChangeDetectionOutcome
import com.example.EdgeMemo.core.sync.SyncPullSummary
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeBatch
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource
import com.example.EdgeMemo.data.cloud.HttpCloudKnowledgeRemoteDataSource
import com.example.EdgeMemo.testing.LocalHttpBackend
import com.example.EdgeMemo.testing.respond
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
 * Phase 12B.9 — cloud → local pull, written through the PRODUCTION
 * [QdrantEdgeRecordStore] (real JNI → qdrant-edge) and asserted from the
 * shard. The cloud source is the existing `GET /knowledge` contract: the
 * production Kotlin pull adapter speaks it, and deterministic fixtures
 * implement the frozen classification semantics.
 *
 * Resolution of recorded conflicts is NOT part of this subphase (12B.10).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QdrantSyncEnginePullTest {

    companion object {
        private const val DIMENSION = 4

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    private var currentTime = 1_700_000_000_000L

    private fun tempDir(): File {
        val d = File.createTempFile("edgememo-12b9-", "")
        d.delete(); d.mkdirs(); return d
    }

    private fun localRecord(
        id: RecordId = RecordId.random(),
        version: Int = 1,
        hash: String? = null,
        tombstone: Boolean = false,
        values: Map<String, JsonValue> = mapOf(
            "title" to JsonValue.fromString("Local note"),
            "content" to JsonValue.fromString("local content"),
        ),
    ): Record = Record(
        id = id,
        recordType = RecordType.MEMORY,
        entityId = null,
        vector = null,
        payload = values,
        version = version,
        createdAt = currentTime,
        updatedAt = currentTime,
        contentHash = hash,
        tombstone = tombstone,
    )

    private fun item(
        recordId: RecordId,
        version: Int = 1,
        hash: String = "a".repeat(64),
        title: String = "Cloud title",
        content: String = "Cloud content",
        tombstone: Boolean = false,
        origin: String = "CLOUD",
        authority: String? = null,
        subjectKey: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ): CloudKnowledgeItem = CloudKnowledgeItem(
        memoryId = recordId.uuid,
        subjectKey = subjectKey,
        title = title,
        content = content,
        contentHash = hash,
        version = version,
        updatedAt = currentTime,
        origin = origin,
        authority = authority,
        supersedes = null,
        tombstone = tombstone,
        metadata = metadata,
    )

    private class FixtureSource(vararg val pages: CloudKnowledgeBatch) : CloudKnowledgeRemoteDataSource {
        var index = 0
        val requestedCursors = mutableListOf<String?>()
        override suspend fun pullKnowledge(cursor: String?): CloudKnowledgeBatch {
            requestedCursors += cursor
            return pages[index++]
        }
    }

    private fun open(dir: File, source: CloudKnowledgeRemoteDataSource): Pair<
        com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore,
        DefaultQdrantSyncEngine,
    > {
        val store = com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(dir)
        runBlocking {
            store.ensureReady(DIMENSION)
            store.ensureIndexes()
        }
        val operations = QdrantSyncOperationStore(store) { currentTime }
        val detector = QdrantChangeDetector(store, operations) { currentTime }
        val engine = DefaultQdrantSyncEngine(
            recordStore = store,
            operationStore = operations,
            detector = detector,
            remote = object : com.example.EdgeMemo.core.sync.QdrantSyncRemote {
                override suspend fun push(operation: com.example.EdgeMemo.core.sync.SyncOperationRecord) =
                    throw AssertionError("pull tests must never push")
            },
            cloudKnowledge = source,
            clock = { currentTime },
        )
        return store to engine
    }

    private fun openReopened(dir: File, source: CloudKnowledgeRemoteDataSource): Pair<
        com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore,
        DefaultQdrantSyncEngine,
    > {
        val store = com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(dir)
        runBlocking { store.open() }
        val operations = QdrantSyncOperationStore(store) { currentTime }
        val detector = QdrantChangeDetector(store, operations) { currentTime }
        val engine = DefaultQdrantSyncEngine(
            recordStore = store,
            operationStore = operations,
            detector = detector,
            remote = object : com.example.EdgeMemo.core.sync.QdrantSyncRemote {
                override suspend fun push(operation: com.example.EdgeMemo.core.sync.SyncOperationRecord) =
                    throw AssertionError("pull tests must never push")
            },
            cloudKnowledge = source,
            clock = { currentTime },
        )
        return store to engine
    }

    // ------------------------------------------------------------------
    // §12 classification coverage — applied against REAL local Qdrant state
    // ------------------------------------------------------------------

    @Test
    fun newCloudRecordIsAppliedWithProvenanceAndNeverEchoesOut() = runBlocking {
        val dir = tempDir()
        val id = RecordId.random()
        val source = FixtureSource(CloudKnowledgeBatch(items = listOf(item(id, hash = "a".repeat(64)))))
        val (store, engine) = open(dir, source)
        try {
            val summary = engine.pullAndApply(pageSize = 20)
            assertEquals(SyncPullSummary(applied = 1, duplicates = 0, stale = 0, conflicts = 0, tombstoned = 0, rejected = 0), summary)

            // Re-read the applied record from Qdrant.
            val applied = store.get(id)
            assertNotNull(applied)
            assertEquals(RecordOrigin_CLOUD, applied!!.origin)
            assertEquals(SyncState.SYNCED, applied.syncState)
            assertEquals(1, applied.version)
            assertEquals("a".repeat(64), applied.contentHash)
            // §7.3 watermark present at apply time → echo impossible.
            assertEquals(1, applied.lastSyncedVersion)
            assertEquals("a".repeat(64), applied.lastSyncedContentHash)

            // Echo-loop guard: detection of the applied record enqueues nothing.
            val outcome = engine.enqueueIfChanged(applied)
            assertEquals(ChangeDetectionOutcome.CloudOriginIgnored, outcome)
            val operations = QdrantSyncOperationStore(store) { currentTime }
            assertEquals(0L, operations.countByState(com.example.EdgeMemo.core.sync.OutboxOperationState.PENDING))
        } finally {
            runCatching { store.close() }
            dir.deleteRecursively()
        }
    }

    private val RecordOrigin_CLOUD = com.example.EdgeMemo.core.record.RecordOrigin.CLOUD

    @Test
    fun updateFromCloudAdvancesLocalAndPreservesLocalEmbedding() = runBlocking {
        val dir = tempDir()
        val id = RecordId.random()
        val existing = localRecord(id = id, version = 1, hash = "a".repeat(64))
        val source = FixtureSource(CloudKnowledgeBatch(items = listOf(item(id, version = 3, hash = "b".repeat(64)))))
        val (store, engine) = open(dir, source)
        try {
            store.upsert(existing.copy(vector = floatArrayOf(1f, 0f, 0f, 0f)))
            val summary = engine.pullAndApply(pageSize = 20)
            assertEquals(1, summary.applied)

            val updated = store.get(id)!!
            assertEquals(3, updated.version)
            assertEquals("b".repeat(64), updated.contentHash)
            assertEquals(RecordOrigin_CLOUD, updated.origin)
            // Vector is preserved (content hashing excludes vectors anyway).
            assertNotNull(updated.vector)
            assertEquals(4, updated.vector!!.size)
        } finally {
            runCatching { store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun duplicateCloudItemDoesNotRewriteLocalState() = runBlocking {
        val dir = tempDir()
        val id = RecordId.random()
        val existing = localRecord(id = id, version = 2, hash = "a".repeat(64))
        val source = FixtureSource(CloudKnowledgeBatch(items = listOf(item(id, version = 2, hash = "a".repeat(64)))))
        val (store, engine) = open(dir, source)
        try {
            store.upsert(existing)
            val summary = engine.pullAndApply(pageSize = 20)
            assertEquals(1, summary.duplicates)
            assertEquals(0, summary.applied)
            // The stored record's updatedAt is unchanged — no rewrite happened.
            val reread = store.get(id)!!
            assertEquals(currentTime, reread.updatedAt)
        } finally {
            runCatching { store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun staleCloudItemNeverOverwritesNewerLocalRecord() = runBlocking {
        val dir = tempDir()
        val id = RecordId.random()
        val local = localRecord(id = id, version = 5, hash = "a".repeat(64))
        val source = FixtureSource(CloudKnowledgeBatch(items = listOf(item(id, version = 3, hash = "b".repeat(64)))))
        val (store, engine) = open(dir, source)
        try {
            store.upsert(local)
            val summary = engine.pullAndApply(pageSize = 20)
            assertEquals(1, summary.stale)
            val reread = store.get(id)!!
            assertEquals(5, reread.version)
            assertEquals("a".repeat(64), reread.contentHash)
        } finally {
            runCatching { store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun cloudTombstoneIsAppliedAndPersistedLocally() = runBlocking {
        val dir = tempDir()
        val id = RecordId.random()
        val local = localRecord(id = id, version = 1, hash = "a".repeat(64))
        val tombstoneItem = item(id, version = 4, hash = "c".repeat(64), tombstone = true)
        val source = FixtureSource(CloudKnowledgeBatch(items = listOf(tombstoneItem)))
        val (store, engine) = open(dir, source)
        try {
            store.upsert(local)
            val summary = engine.pullAndApply(pageSize = 20)
            assertEquals(1, summary.tombstoned)

            val tombstoned = store.get(id)!!
            assertTrue(tombstoned.tombstone)
            assertEquals(4, tombstoned.version)
        } finally {
            runCatching { store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun localTombstoneIsNeverResurrectedByStaleCloudState() = runBlocking {
        val dir = tempDir()
        val id = RecordId.random()
        val tomb = localRecord(id = id, version = 4, hash = "a".repeat(64), tombstone = true)
        val source = FixtureSource(CloudKnowledgeBatch(items = listOf(item(id, version = 3, hash = "a".repeat(64)))))
        val (store, engine) = open(dir, source)
        try {
            store.upsert(tomb)
            val summary = engine.pullAndApply(pageSize = 20)
            assertEquals(1, summary.stale)
            val stillDead = store.get(id)!!
            assertTrue("resurrection must be blocked", stillDead.tombstone)
        } finally {
            runCatching { store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun sameVersionDifferentHashRecordsConflictAndNeverOverwrites() = runBlocking {
        val dir = tempDir()
        val id = RecordId.random()
        val local = localRecord(id = id, version = 2, hash = "a".repeat(64))
        val source = FixtureSource(CloudKnowledgeBatch(items = listOf(item(id, version = 2, hash = "b".repeat(64)))))
        val (store, engine) = open(dir, source)
        try {
            store.upsert(local)
            val summary = engine.pullAndApply(pageSize = 20)
            assertEquals(1, summary.conflicts)
            assertEquals(0, summary.applied)

            // Local evidence preserved: nothing overwritten.
            val reread = store.get(id)!!
            assertEquals("a".repeat(64), reread.contentHash)
            assertEquals(2, reread.version)

            // A conflict record exists as a payload-only point.
            val conflictId = QdrantConflictRecorder.conflictPointId(
                subject = "",
                localRecordId = id.uuid,
                localContentHash = "a".repeat(64),
                incomingRecordId = id.uuid,
                incomingContentHash = "b".repeat(64),
            )
            val conflict = store.get(conflictId)
            assertNotNull(conflict)
            assertEquals(RecordType.CONFLICT, conflict!!.recordType)
            assertNull(conflict.vector)
            assertEquals("UNRESOLVED", (conflict.payload["state"] as JsonString).value)
            assertEquals("PULL_CONFLICT", (conflict.payload["reason"] as JsonString).value)
            // Provenance fields preserved on both sides.
            assertEquals("a".repeat(64), (conflict.payload["local_content_hash"] as JsonString).value)
            assertEquals("b".repeat(64), (conflict.payload["incoming_content_hash"] as JsonString).value)
        } finally {
            runCatching { store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun repeatedPullIsIdempotentAndConflictNotDuplicated() = runBlocking {
        val dir = tempDir()
        val id = RecordId.random()
        val local = localRecord(id = id, version = 2, hash = "a".repeat(64))
        val pages = arrayOf(
            CloudKnowledgeBatch(items = listOf(item(id, version = 2, hash = "b".repeat(64)))),
            CloudKnowledgeBatch(items = listOf(item(id, version = 2, hash = "b".repeat(64)))),
        )
        val source = FixtureSource(*pages)
        val (store, engine) = open(dir, source)
        try {
            store.upsert(local)
            engine.pullAndApply(pageSize = 20)
            engine.pullAndApply(pageSize = 20)
            val conflicts = store.count(
                RecordQuery.Count(
                    filter = com.example.EdgeMemo.core.record.RecordFilter.recordType(RecordType.CONFLICT),
                ),
            )
            assertEquals(1L, conflicts)
        } finally {
            runCatching { store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun appliedNewCloudRecordNeverGeneratesSyncOperations() = runBlocking {
        val dir = tempDir()
        val id = RecordId.random()
        val source = FixtureSource(CloudKnowledgeBatch(items = listOf(item(id))))
        val (store, engine) = open(dir, source)
        try {
            engine.pullAndApply(pageSize = 20)
            val applied = store.get(id)!!
            val outcome = engine.enqueueIfChanged(applied)
            assertEquals(ChangeDetectionOutcome.CloudOriginIgnored, outcome)
        } finally {
            runCatching { store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun pullRejectsMalformedCloudItems() = runBlocking {
        val dir = tempDir()
        val badId = item(RecordId.random()).copy(memoryId = "not-a-uuid")
        val badHash = item(RecordId.random()).copy(contentHash = "xyz")
        val badVersion = item(RecordId.random()).copy(version = 0)
        val source = FixtureSource(CloudKnowledgeBatch(items = listOf(badId, badHash, badVersion)))
        val (store, engine) = open(dir, source)
        try {
            val summary = engine.pullAndApply(pageSize = 20)
            assertEquals(3, summary.rejected)
            assertEquals(0, summary.applied)
        } finally {
            runCatching { store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun cursorSurvivesProcessDeathAndResumesWithoutReprocessing() = runBlocking {
        val dir = tempDir()
        val idA = RecordId.random()
        val idB = RecordId.random()
        val source = FixtureSource(
            CloudKnowledgeBatch(items = listOf(item(idA)), nextCursor = "cur-1"),
            CloudKnowledgeBatch(items = listOf(item(idB)), nextCursor = null),
        )
        val (store, engine) = open(dir, source)
        try {
            store.upsert(localRecord(id = RecordId.random()))
            val s1 = engine.pullAndApply(pageSize = 20)
            assertEquals(1, s1.applied)
            assertEquals("cur-1", s1.nextCursor)
            store.close()

            val (store2, engine2) = openReopened(dir, source)
            val s2 = engine2.pullAndApply(pageSize = 20)
            assertEquals(1, s2.applied)
            assertEquals(listOf(null, "cur-1"), source.requestedCursors)
            store2.close()
        } finally {
            runCatching { store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun syncEchoLoopIsImpossibleByWatermarkAndProvenance() = runBlocking {
        // Local → cloud → detection again must produce NO second operation,
        // and a cloud-applied record must never generate an outgoing one.
        val dir = tempDir()
        val id = RecordId.random()
        val synced = localRecord(id = id, version = 1, hash = "a".repeat(64)).copy(
            origin = com.example.EdgeMemo.core.record.RecordOrigin.CLOUD,
            syncState = SyncState.SYNCED,
            syncDecision = com.example.EdgeMemo.core.record.SyncDecision.SYNC,
            lastSyncedVersion = 1,
            lastSyncedContentHash = "a".repeat(64),
        )
        val source = FixtureSource(CloudKnowledgeBatch(items = listOf(item(id, version = 1, hash = "a".repeat(64)))))
        val (store, engine) = open(dir, source)
        try {
            store.upsert(synced)
            // Re-pull of the identical cloud state: no rewrite, no operation.
            val s1 = engine.pullAndApply(pageSize = 20)
            assertEquals(1, s1.duplicates)
            assertEquals(
                ChangeDetectionOutcome.CloudOriginIgnored,
                engine.enqueueIfChanged(store.get(id)!!),
            )

            // A device-origin record already confirmed at v1: re-detection is
            // DUPLICATE (watermark hit), never a new intent.
            val device = localRecord(version = 2).copy(
                syncState = SyncState.SYNCED,
                syncDecision = com.example.EdgeMemo.core.record.SyncDecision.SYNC,
                lastSyncedVersion = 2,
            )
            store.upsert(device)
            val reread = store.get(device.id)!!
            val canonical = com.example.EdgeMemo.core.sync.CanonicalContentHash.hash(reread.payload)
            store.upsert(reread.copy(lastSyncedContentHash = canonical))
            val outcome = engine.enqueueIfChanged(store.get(device.id)!!)
            assertTrue(
                "synced identical version must not echo: $outcome",
                outcome is ChangeDetectionOutcome.NoChange &&
                    (outcome as ChangeDetectionOutcome.NoChange).classification ==
                    com.example.EdgeMemo.core.sync.SyncClassification.DUPLICATE,
            )
        } finally {
            runCatching { store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun productionKotlinPullAdapterSpeaksTheCloudContract() = runBlocking<Unit> {
        val dir = tempDir()
        val id = RecordId.random()
        val cloud = LocalHttpBackend.start { exchange ->
            val items = org.json.JSONArray().apply {
                put(
                    org.json.JSONObject().apply {
                        put("memoryId", id.uuid)
                        put("title", "Curated")
                        put("content", "Cloud knowledge")
                        put("contentHash", "d".repeat(64))
                        put("version", 2)
                        put("updatedAt", currentTime)
                        put("origin", "CLOUD")
                    },
                )
            }
            exchange.respond(200, org.json.JSONObject().put("items", items).put("nextCursor", org.json.JSONObject.NULL).toString())
        }
        try {
            val source = HttpCloudKnowledgeRemoteDataSource(cloud.baseUrl)
            val (store, engine) = open(dir, source)
            val s = engine.pullAndApply(pageSize = 20)
            assertEquals(1, s.applied)
            assertEquals(2, store.get(id)!!.version)
            runCatching { store.close() }
            dir.deleteRecursively()
        } finally {
            cloud.close()
        }
    }
}
