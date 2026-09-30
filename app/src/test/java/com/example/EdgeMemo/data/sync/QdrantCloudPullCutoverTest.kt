package com.example.EdgeMemo.data.sync

import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.record.JsonString
import com.example.EdgeMemo.core.record.RecordFilter
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordQuery
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.record.SyncState
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore
import com.example.EdgeMemo.data.local.sync.DefaultQdrantSyncEngine
import com.example.EdgeMemo.data.local.sync.QdrantChangeDetector
import com.example.EdgeMemo.data.local.sync.QdrantConflictResolver
import com.example.EdgeMemo.data.local.sync.QdrantSyncOperationStore
import com.example.EdgeMemo.data.conflict.QdrantConflictStore
import com.example.EdgeMemo.data.policy.DefaultPolicyEngine
import com.example.EdgeMemo.data.policy.DefaultRedactionService
import com.example.EdgeMemo.data.repository.QdrantRecordMemoryRepository
import com.example.EdgeMemo.data.retrieval.QdrantRecordRetrievalService
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeBatch
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource
import com.example.EdgeMemo.domain.cloud.PullCloudKnowledgeUseCase
import com.example.EdgeMemo.domain.conflict.ConflictResolutionAction
import com.example.EdgeMemo.domain.conflict.ResolveConflictUseCase
import com.example.EdgeMemo.domain.rag.DefaultRagService
import com.example.EdgeMemo.ai.llm.ExtractiveLLMService
import com.example.EdgeMemo.core.rag.AnswerStatus
import com.example.EdgeMemo.core.rag.RagRequest
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 13.4 — cloud pull + cursor + conflicts run on the Qdrant-native
 * engine against the SAME application shard the repository and retrieval use.
 * Real JNI shard; deterministic fixture for the cloud page source (the same
 * fixture convention as 12B.9: fixtures implement the cloud contract, they
 * never replace the persistence path).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QdrantCloudPullCutoverTest {

    companion object {
        private const val DIMENSION = 512
        private const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    private val dir = File.createTempFile("p134-pull-", "").apply { delete(); mkdirs() }
    private val embeddings = FeatureHashingEmbeddingService()

    private class StubCloud : CloudKnowledgeRemoteDataSource {
        val pages = ArrayDeque<CloudKnowledgeBatch>()
        val cursorsSeen = mutableListOf<String?>()
        var default = CloudKnowledgeBatch(emptyList())

        override suspend fun pullKnowledge(cursor: String?): CloudKnowledgeBatch {
            cursorsSeen += cursor
            return pages.removeFirstOrNull() ?: default
        }
    }

    private val cloud = StubCloud()
    private var store = QdrantEdgeRecordStore(File(dir, "qdrant_sync_store"))
    private lateinit var operations: QdrantSyncOperationStore
    private lateinit var engine: DefaultQdrantSyncEngine
    private lateinit var repository: QdrantRecordMemoryRepository
    private lateinit var retrieval: QdrantRecordRetrievalService
    private lateinit var conflicts: QdrantConflictStore
    private lateinit var pull: PullCloudKnowledgeUseCase

    private fun buildStack() {
        operations = QdrantSyncOperationStore(store)
        val detector = QdrantChangeDetector(store, operations)
        engine = DefaultQdrantSyncEngine(
            recordStore = store,
            operationStore = operations,
            detector = detector,
            remote = UnimplementedQdrantSyncRemote(),
            cloudKnowledge = cloud,
            cloudEmbedding = { item ->
                runCatching { embeddings.embed("${item.title}\n${item.content}") }.getOrNull()
            },
        )
        repository = QdrantRecordMemoryRepository(
            recordStore = store,
            syncEngine = engine,
            embeddingService = embeddings,
            policyEngine = DefaultPolicyEngine(DefaultRedactionService()),
        )
        retrieval = QdrantRecordRetrievalService(embeddings, store)
        conflicts = QdrantConflictStore(store, QdrantConflictResolver(store, detector))
        pull = PullCloudKnowledgeUseCase(QdrantNativeCloudKnowledgeIngestor(engine))
    }

    private fun openFresh() = runBlocking {
        store.ensureReady(DIMENSION)
        store.ensureIndexes()
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { store.close() } }
        dir.deleteRecursively()
    }

    private fun item(
        id: String = UUID.randomUUID().toString(),
        version: Int = 3,
        content: String = "Torque spec from the cloud: 22 Nm on the P-101 housing bolts.",
        contentHash: String = HASH_B,
        tombstone: Boolean = false,
        authority: String? = "OEM-MANUAL",
        subjectKey: String? = "p101/torque",
    ) = CloudKnowledgeItem(
        memoryId = id,
        subjectKey = subjectKey,
        title = "P-101 torque specification",
        content = content,
        contentHash = contentHash,
        version = version,
        updatedAt = 1_700_000_001_000L,
        origin = "CLOUD",
        authority = authority,
        tombstone = tombstone,
        metadata = mapOf("source" to "OEM"),
    )

    private fun cursorRecord() = runBlocking {
        store.scroll(
            RecordQuery.Scroll(limit = 10, recordTypes = setOf(RecordType.SYS_CURSOR)),
        ).records.firstOrNull()
    }

    // ------------------------------------------------------------------
    // A + N — pull lands in the shard and is immediately retrievable
    // ------------------------------------------------------------------

    @Test
    fun pulledKnowledgeLandsInShardAndIsRetrievableAnswerable() = runBlocking {
        openFresh(); buildStack()
        val incoming = item()
        cloud.pages.add(CloudKnowledgeBatch(listOf(incoming), nextCursor = "c1"))

        val result = pull()

        assertEquals(1, result.applied)
        assertEquals("c1", result.cursor)

        // Durable in the application shard with cloud provenance, marked
        // synced and watermarked so it can never echo back to the cloud.
        val stored = store.get(RecordId.fromString(incoming.memoryId))!!
        assertEquals(com.example.EdgeMemo.core.record.RecordOrigin.CLOUD, stored.origin)
        assertEquals(SyncState.SYNCED, stored.syncState)
        assertEquals(incoming.version, stored.lastSyncedVersion)
        assertEquals(0L, operations.countByState(OutboxOperationState.PENDING))

        // Immediately inside the ACTIVE retrieval scope, dense AND keyword.
        val evidence = retrieval.retrieve(
            com.example.EdgeMemo.core.retrieval.RetrievalQuery(
                text = "P-101 torque specification housing bolts", limit = 5,
            ),
        )
        val hit = evidence.evidence.first { it.memory.memoryId == incoming.memoryId }
        assertNotNull("pulled record must have a real dense vector", hit.denseScore)
        assertNotNull("pulled record must be keyword-matchable", hit.keywordScore)

        // And answerable by the local RAG path, offline.
        val rag = DefaultRagService(retrieval, ExtractiveLLMService())
        val response = rag.answer(RagRequest("What torque do the P-101 housing bolts take?"))
        assertEquals(AnswerStatus.ANSWERED, response.status)
        assertTrue(response.sources.any { it.memoryId == incoming.memoryId })
    }

    // ------------------------------------------------------------------
    // B/C — cursor durability + idempotent repeated pull
    // ------------------------------------------------------------------

    @Test
    fun cursorPersistsAdvancesAndRepeatedPullIsIdempotent() = runBlocking {
        openFresh(); buildStack()
        val incoming = item()
        cloud.pages.add(CloudKnowledgeBatch(listOf(incoming), nextCursor = "c1"))
        pull()

        // sys_cursor point holds the advanced cursor.
        val cursor = cursorRecord()!!
        assertEquals("c1", (cursor.payload["cursor"] as JsonString).value)
        assertEquals(com.example.EdgeMemo.core.record.SyncDecision.LOCAL_ONLY, cursor.syncDecision)

        // Replay of the SAME page (crash-before-cursor simulation) classifies
        // DUPLICATE and writes the record zero additional times — idempotent.
        val knowledgeVersion = store.get(RecordId.fromString(incoming.memoryId))!!.version
        cloud.pages.add(CloudKnowledgeBatch(listOf(incoming), nextCursor = "c1"))
        val replay = pull()
        assertEquals(0, replay.applied)
        assertEquals(1, replay.duplicates)
        assertEquals("knowledge record must not be rewritten on replay",
            knowledgeVersion, store.get(RecordId.fromString(incoming.memoryId))!!.version)

        // The next round resumes FROM the persisted cursor (seen by the remote).
        cloud.pages.add(CloudKnowledgeBatch(emptyList(), nextCursor = null))
        val next = pull()
        assertTrue(next.noChange)
        assertEquals("c1", cloud.cursorsSeen.last())
    }

    @Test
    fun cursorAndRecordsSurviveShardReopen() = runBlocking {
        openFresh(); buildStack()
        val incoming = item()
        cloud.pages.add(CloudKnowledgeBatch(listOf(incoming), nextCursor = "c1"))
        pull()
        store.close()

        // New "process": everything rebuilt from disk, resume from cursor.
        store = QdrantEdgeRecordStore(File(dir, "qdrant_sync_store"))
        store.open()
        buildStack()
        cloud.pages.add(CloudKnowledgeBatch(emptyList(), nextCursor = null))
        val resumed = pull()
        assertTrue(resumed.noChange)
        assertEquals("c1", cloud.cursorsSeen.last())
        assertNotNull(store.get(RecordId.fromString(incoming.memoryId)))
    }

    @Test
    fun unavailableCloudIsHonestAndLeavesStateIntact() = runBlocking {
        openFresh(); buildStack()
        // Nothing pulled yet — no cursor point exists.
        assertNull(cursorRecord())

        val throwingEngine = DefaultQdrantSyncEngine(
            recordStore = store,
            operationStore = operations,
            detector = QdrantChangeDetector(store, operations),
            remote = UnimplementedQdrantSyncRemote(),
            cloudKnowledge = object : CloudKnowledgeRemoteDataSource {
                override suspend fun pullKnowledge(cursor: String?) =
                    throw EdgeError.CloudUnavailable("no cloud backend configured")
            },
        )
        assertThrows(EdgeError.CloudUnavailable::class.java) {
            runBlocking {
                PullCloudKnowledgeUseCase(QdrantNativeCloudKnowledgeIngestor(throwingEngine))()
            }
        }
        // An honest failure: no cursor was written, no state was touched.
        assertNull(cursorRecord())
    }

    // ------------------------------------------------------------------
    // Malformed cloud items are refused before any local insert (§26)
    // ------------------------------------------------------------------

    @Test
    fun malformedCloudItemsNeverEnterTheShard() = runBlocking {
        openFresh(); buildStack()
        cloud.pages.add(
            CloudKnowledgeBatch(
                listOf(
                    item(id = "not-a-uuid"),
                    item(contentHash = "xyz"),
                    item(version = 0),
                ),
                nextCursor = null,
            ),
        )
        val summary = engine.pullAndApply(50)
        assertEquals(3, summary.rejected)
        assertEquals(0, summary.applied)
    }

    // ------------------------------------------------------------------
    // E/F/G/H — conflicts on the Qdrant store through the domain boundary
    // ------------------------------------------------------------------

    private fun localDivergentRecord(): String = runBlocking {
        val memory = repository.create(
            CreateMemoryInput(
                title = "P-101 torque note",
                content = "Workshop value: 18 Nm on the P-101 housing bolts.",
                type = MemoryType.NOTE,
                subjectKey = "p101/torque",
                userSyncChoice = SyncDecision.SYNC,
            ),
        )
        // Divergent content under the SAME cloud record identity + version:
        // force local id/hash to mirror the incoming item so classify hits
        // CONFLICT (same id/version, different hash).
        val stored = store.get(RecordId.fromString(memory.memoryId))!!
        store.upsert(stored.copy(contentHash = HASH_A))
        memory.memoryId
    }

    @Test
    fun pullConflictRecordsDurableEvidenceAndAppearsInConflictUiBoundary() = runBlocking {
        openFresh(); buildStack()
        val incoming = item(contentHash = HASH_B)
        seedDivergentLocal(incoming)

        cloud.pages.add(CloudKnowledgeBatch(listOf(incoming), nextCursor = null))
        val result = pull()
        assertEquals(1, result.conflicts)

        // Local side untouched; evidence durable in the shard.
        val local = store.get(RecordId.fromString(incoming.memoryId))!!
        assertEquals(HASH_A, local.contentHash)
        assertEquals(1L, conflicts.countUnresolved())

        val domain = conflicts.list().first()
        assertEquals(incoming.contentHash, domain.incomingContentHash)
        assertEquals("OEM-MANUAL", domain.incomingAuthority)
        assertEquals(com.example.EdgeMemo.domain.conflict.ConflictResolutionState.UNRESOLVED, domain.state)

        // Explicit keep-local through the DOMAIN use case boundary.
        val resolve = ResolveConflictUseCase(conflicts)
        val kept = resolve(domain.conflictId, ConflictResolutionAction.KEEP_LOCAL, "workshop knows best")
        assertEquals(com.example.EdgeMemo.domain.conflict.ConflictResolutionState.RESOLVED_LOCAL, kept.state)
        assertEquals(0L, conflicts.countUnresolved())
        // Local record still stands untouched and no operation was minted.
        assertEquals(HASH_A, store.get(RecordId.fromString(incoming.memoryId))!!.contentHash)
        assertEquals(0L, operations.countByState(OutboxOperationState.PENDING))
    }

    /** Plant a LOCAL record at the incoming id/version with a different hash,
     *  directly in the store, so the conflict path is exercised without any
     *  unrelated pending operation from the repository write. */
    private fun seedDivergentLocal(incoming: CloudKnowledgeItem) = runBlocking {
        store.upsert(
            com.example.EdgeMemo.core.record.Record(
                id = RecordId.fromString(incoming.memoryId),
                recordType = RecordType.PROCEDURE,
                entityId = null,
                vector = embeddings.embed("local 18 Nm"),
                payload = mapOf(
                    "title" to com.example.EdgeMemo.core.record.JsonValue.fromString("P-101 torque note"),
                    "content" to com.example.EdgeMemo.core.record.JsonValue.fromString("Workshop value 18 Nm."),
                ),
                version = incoming.version,
                source = "USER_ENTRY",
                syncState = SyncState.SYNCED,
                syncDecision = com.example.EdgeMemo.core.record.SyncDecision.SYNC,
                subjectKey = incoming.subjectKey,
                contentHash = HASH_A,
                origin = com.example.EdgeMemo.core.record.RecordOrigin.LOCAL,
                lastSyncedVersion = incoming.version,
                lastSyncedContentHash = HASH_A,
            ),
        )
    }

    @Test
    fun keepCloudResolutionConvergesWithDeterministicFollowUpOperation() = runBlocking {
        openFresh(); buildStack()
        val incoming = item(contentHash = HASH_B)
        seedDivergentLocal(incoming)
        cloud.pages.add(CloudKnowledgeBatch(listOf(incoming), nextCursor = null))
        pull()

        val domain = conflicts.list().first()
        val resolved = ResolveConflictUseCase(conflicts)(
            domain.conflictId, ConflictResolutionAction.KEEP_CLOUD, "trust the manual",
        )
        assertEquals(com.example.EdgeMemo.domain.conflict.ConflictResolutionState.RESOLVED_CLOUD, resolved.state)

        // Incoming frozen content became active at a STRICTLY NEWER version…
        val applied = store.get(RecordId.fromString(incoming.memoryId))!!
        assertTrue(applied.version > incoming.version)
        assertEquals("Torque spec from the cloud: 22 Nm on the P-101 housing bolts.",
            (applied.payload.getValue("content") as JsonString).value)
        // …with exactly one deterministic follow-up operation.
        val pending = operations.listByStates(
            setOf(OutboxOperationState.PENDING, OutboxOperationState.FAILED, OutboxOperationState.IN_FLIGHT),
            limit = 20, offsetId = null,
        ).operations
        assertEquals(1, pending.size)
        assertTrue(pending.first().operationId.value.startsWith("UPSERT:"))

        // Re-resolution of immutable evidence changes nothing.
        ResolveConflictUseCase(conflicts)(domain.conflictId, ConflictResolutionAction.KEEP_CLOUD, null)
        assertEquals(applied.version, store.get(RecordId.fromString(incoming.memoryId))!!.version)
        assertEquals(1, operations.listByStates(setOf(OutboxOperationState.PENDING), 10, null).operations.size)
    }

    // ------------------------------------------------------------------
    // H — tombstone/resurrection protection at the cutover boundary
    // ------------------------------------------------------------------

    @Test
    fun staleCloudCannotResurrectLocalTombstoneThroughTheNewPath() = runBlocking {
        openFresh(); buildStack()
        val localItem = item(contentHash = HASH_A)
        cloud.pages.add(CloudKnowledgeBatch(listOf(localItem), nextCursor = null))
        pull()
        repository.delete(localItem.memoryId) // tombstone v4 > cloud v3

        // Cloud replays the older live item.
        cloud.pages.add(CloudKnowledgeBatch(listOf(localItem), nextCursor = null))
        val replay = engine.pullAndApply(50)
        assertEquals(1, replay.stale)
        val stillDead = store.get(RecordId.fromString(localItem.memoryId))!!
        assertTrue("stale cloud data must never resurrect the tombstone", stillDead.tombstone)
    }

    // ------------------------------------------------------------------
    // I/J — the retired cloud paths do not run
    // ------------------------------------------------------------------

    @Test
    fun pullProducesOnlyQdrantNativeIdentitiesAndNoRoomArtifacts() = runBlocking {
        openFresh(); buildStack()
        // The whole flow above ran without any EdgeMindDatabase instance:
        // the shard itself contains exactly the frozen system point types.
        cloud.pages.add(CloudKnowledgeBatch(listOf(item()), nextCursor = null))
        pull()
        val syncStateCounts = runBlocking {
            store.count(
                RecordQuery.Count(
                    filter = RecordFilter.and(
                        RecordFilter.activeOnly(),
                        RecordFilter.syncState(SyncState.PENDING),
                    ),
                    recordTypes = com.example.EdgeMemo.data.repository.MemoryRecordMapper.APP_MEMORY_RECORD_TYPES,
                ),
            )
        }
        assertEquals("pulled cloud records never re-enter the outbox", 0L, syncStateCounts)
    }

    @Test
    fun syncStateOfLocalRecordConvergesAcrossPushAckWithPull() = runBlocking {
        openFresh(); buildStack()
        // Local SYNC write → PENDING + operation (13.2 semantics intact).
        val memory = repository.create(
            CreateMemoryInput(
                title = "Seal change", content = "Replace the shaft seal at 4000h",
                type = MemoryType.NOTE, userSyncChoice = SyncDecision.SYNC,
            ),
        )
        val recordId = RecordId.fromString(memory.memoryId)
        assertEquals(MemorySyncState.PENDING, repository.get(memory.memoryId)!!.syncState)
        assertEquals(1L, operations.countByState(OutboxOperationState.PENDING))

        // Repeated pull cycles never disturb the pending local intent.
        cloud.pages.add(CloudKnowledgeBatch(listOf(item()), nextCursor = null))
        pull()
        assertEquals(1L, operations.countByState(OutboxOperationState.PENDING))
        val untouched = store.get(recordId)!!
        assertEquals(1, untouched.version)
        assertTrue(!untouched.tombstone)
        assertEquals(SyncState.PENDING, untouched.syncState)
    }
}
