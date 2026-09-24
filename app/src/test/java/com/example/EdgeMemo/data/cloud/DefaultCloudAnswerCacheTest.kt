package com.example.EdgeMemo.data.cloud

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.ai.embedding.EmbeddingService
import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.retrieval.QueryNormalizer
import com.example.EdgeMemo.data.conflict.DefaultConflictResolver
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.repository.DefaultMemoryRepository
import com.example.EdgeMemo.domain.cloud.CacheCloudAnswerResult
import com.example.EdgeMemo.domain.cloud.CloudAnswerCache
import com.example.EdgeMemo.domain.conflict.ConflictResolutionState
import com.example.EdgeMemo.native.qdrant.LocalVectorStore
import com.example.EdgeMemo.native.qdrant.QdrantEdgeVectorStore
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Explicit cloud-answer localization path (Phase 8). Only NEW knowledge may be
 * persisted; identical answers are a no-op; divergent local knowledge for the
 * same subject refuses to overwrite AND is persisted as a resolvable
 * ConflictEntity through the Phase 7 conflict system.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DefaultCloudAnswerCacheTest {

    private lateinit var context: Context
    private lateinit var sandbox: File

    @Before
    fun setUp() {
        TestNativeLoader.ensureLoaded()
        context = ApplicationProvider.getApplicationContext()
        sandbox = File(context.cacheDir, "phase8-cache-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    @Test
    fun saveStoresCloudOriginAnswerSearchableAndNeverInOutbox() = runBlocking {
        val stack = newStack()
        try {
            val result = stack.cache.save(
                question = "What is the latest approved procedure revision?",
                answer = "The latest approved procedure revision is P-101 revision 4.",
                authority = "central-engineering",
            )

            assertTrue(result is CacheCloudAnswerResult.Saved)
            val memory = (result as CacheCloudAnswerResult.Saved).memory
            assertEquals(MemoryOrigin.CLOUD, memory.origin)
            assertEquals(MemoryType.CLOUD_KNOWLEDGE, memory.type)

            val row = stack.database.memoryDao().getById(memory.memoryId)
            assertEquals("CLOUD", row?.origin)
            assertEquals("CLOUD_KNOWLEDGE", row?.type)
            assertEquals("SYNCED", row?.syncState)
            assertEquals("central-engineering", row?.authority)

            val hits = stack.repository.search("approved procedure revision", 5)
            assertTrue(hits.any { it.memory.memoryId == memory.memoryId })

            // cloud-origin knowledge must never be pushed back
            assertEquals(0L, stack.database.syncOutboxDao().countAll())
        } finally {
            stack.close()
        }
    }

    @Test
    fun identicalAnswerIsNoOpWithoutSecondVector() = runBlocking {
        val stack = newStack()
        try {
            val question = "What is the P-101 torque specification?"
            val answer = "The P-101 torque specification is 45 Nm."
            stack.cache.save(question, answer, null)

            val second = stack.cache.save(question, answer, null)
            assertSame(CacheCloudAnswerResult.AlreadyPresent, second)

            stack.store.ensureReady(stack.embedding.dimension)
            assertEquals(1L, stack.store.count())
            assertEquals(1L, stack.database.memoryDao().count())
        } finally {
            stack.close()
        }
    }

    @Test
    fun conflictingAnswerForSameSubjectIsRefusedWithoutOverwrite() = runBlocking {
        val stack = newStack()
        try {
            val question = "What is the approved P-101 torque?"
            val subject = answerSubject(question)
            val local = stack.repository.create(
                CreateMemoryInput(
                    title = "P-101 torque local",
                    content = "Local record says the approved P-101 torque is 40 Nm.",
                    subjectKey = subject,
                ),
            )

            val result = stack.cache.save(
                question = question,
                answer = "The approved P-101 torque is 52 Nm per cloud.",
                authority = "cloud-hub",
            )

            assertTrue(result is CacheCloudAnswerResult.ConflictPrevented)
            // no silent overwrite
            val still = stack.repository.get(local.memoryId)
            assertEquals("Local record says the approved P-101 torque is 40 Nm.", still?.content)
            assertEquals(1, stack.repository.list().size)
            assertEquals(0L, stack.database.syncOutboxDao().countAll())
        } finally {
            stack.close()
        }
    }

    @Test
    fun blankQuestionIsRejectedWithTypedErrorAndNoState() = runBlocking {
        val stack = newStack()
        try {
            assertThrows(EdgeError.InvalidInput::class.java) {
                runBlocking { stack.cache.save("   ", "Some cloud answer.", "hub") }
            }
            assertEquals(0, stack.repository.list().size)
            assertEquals(0L, stack.database.syncOutboxDao().countAll())
        } finally {
            stack.close()
        }
    }

    @Test
    fun blankAnswerIsRejectedWithTypedErrorAndNoState() = runBlocking {
        val stack = newStack()
        try {
            assertThrows(EdgeError.InvalidInput::class.java) {
                runBlocking { stack.cache.save("What is the torque?", "   ", "hub") }
            }
            assertEquals(0, stack.repository.list().size)
            assertEquals(0L, stack.database.syncOutboxDao().countAll())
        } finally {
            stack.close()
        }
    }

    @Test
    fun oversizedCloudAnswerIsRejectedBeforePersistence() = runBlocking {
        val stack = newStack()
        try {
            val huge = "x".repeat(DefaultCloudAnswerCache.MAX_ANSWER_CHARS + 1)
            assertThrows(EdgeError.InvalidInput::class.java) {
                runBlocking { stack.cache.save("What is the torque?", huge, "hub") }
            }
            assertEquals(0, stack.repository.list().size)
            assertEquals(0L, stack.database.syncOutboxDao().countAll())
        } finally {
            stack.close()
        }
    }

    @Test
    fun cloudAnswerContainingLocalOnlyClassifiedContentIsStoredButNeverEnqueued() = runBlocking {
        val stack = newStack()
        try {
            val result = stack.cache.save(
                "What is the gate code for site 7?",
                "Gate code for site 7 is 4412.",
                "cloud-hub",
            )

            assertTrue(result is CacheCloudAnswerResult.Saved)
            assertEquals(
                "cloud-origin rows never enter the outbox, even with LOCAL_ONLY-classified content",
                0L,
                stack.database.syncOutboxDao().countAll(),
            )
            assertTrue(stack.repository.search("gate code site 7", 5).isNotEmpty())
        } finally {
            stack.close()
        }
    }

    @Test
    fun savedAnswerSurvivesRoomAndShardRestart() = runBlocking {
        val dbName = "phase8-cache-restart-${UUID.randomUUID()}"
        val qdrantDir = File(sandbox, "cache-restart-qdrant")

        val first = newStack(dbName = dbName, qdrantDir = qdrantDir)
        val saved = first.cache.save(
            "What torque should I use for P-101?",
            "Use 45 Nm torque for the P-101 cap screws.",
            "central-engineering",
        )
        first.close()

        val second = newStack(dbName = dbName, qdrantDir = qdrantDir)
        try {
            val hits = second.repository.search("p-101 torque cap screws", 5)
            assertTrue(hits.isNotEmpty())
            assertTrue(hits.any { it.memory.memoryId == (saved as CacheCloudAnswerResult.Saved).memory.memoryId })
        } finally {
            second.close()
        }
    }

    @Test
    fun divergentAnswerCreatesPersistedConflictPreservingBothSidesEvidence() = runBlocking {
        val stack = newStack()
        try {
            val question = "What is the approved P-101 torque?"
            val subject = answerSubject(question)
            val local = stack.repository.create(
                CreateMemoryInput(
                    title = "P-101 torque local",
                    content = "Local record says the approved P-101 torque is 40 Nm.",
                    subjectKey = subject,
                ),
            )
            val answer = "The approved P-101 torque is 52 Nm per cloud."

            val result = stack.cache.save(question, answer, "cloud-hub")

            assertTrue(result is CacheCloudAnswerResult.ConflictPrevented)
            assertTrue((result as CacheCloudAnswerResult.ConflictPrevented).reason.contains("conflict"))

            val conflicts = stack.database.conflictDao().listAll()
            assertEquals(1, conflicts.size)
            val row = conflicts.single()
            assertEquals(ConflictResolutionState.UNRESOLVED.name, row.state)
            assertEquals(local.memoryId, row.localMemoryId)
            assertEquals("Local record says the approved P-101 torque is 40 Nm.", row.localContent)
            assertEquals(answer, row.incomingContent)
            assertEquals("LOCAL", row.localOrigin)
            assertEquals("CLOUD", row.incomingOrigin)
            assertEquals("cloud-hub", row.incomingAuthority)
            assertEquals(local.version, row.localVersion)
            assertTrue(row.reason.isNotBlank())

            // local record untouched, no silent overwrite, nothing uploaded
            val still = stack.repository.get(local.memoryId)
            assertEquals("Local record says the approved P-101 torque is 40 Nm.", still?.content)
            assertEquals(1, stack.repository.list().size)
            assertEquals(0L, stack.database.syncOutboxDao().countAll())
        } finally {
            stack.close()
        }
    }

    @Test
    fun divergentConflictSurvivesRoomAndShardRestart() = runBlocking {
        val dbName = "phase8-conflict-restart-${UUID.randomUUID()}"
        val qdrantDir = File(sandbox, "conflict-restart-qdrant")
        val question = "What is the approved P-101 torque?"
        val subject = answerSubject(question)

        val first = newStack(dbName = dbName, qdrantDir = qdrantDir)
        first.repository.create(
            CreateMemoryInput("P-101 torque local", "Local says 40 Nm.", subjectKey = subject),
        )
        val result = first.cache.save(question, "Cloud says 52 Nm.", "cloud-hub")
        assertTrue(result is CacheCloudAnswerResult.ConflictPrevented)
        val conflictId = first.database.conflictDao().listAll().single().conflictId
        first.close()

        val second = newStack(dbName = dbName, qdrantDir = qdrantDir)
        try {
            val row = second.database.conflictDao().getById(conflictId)
            assertEquals(ConflictResolutionState.UNRESOLVED.name, row?.state)
            assertEquals("Cloud says 52 Nm.", row?.incomingContent)
        } finally {
            second.close()
        }
    }

    @Test
    fun keepLocalResolutionPreservesLocalRecordAndEvidence() = runBlocking {
        val stack = newStack()
        try {
            val question = "What is the approved P-101 torque?"
            val subject = answerSubject(question)
            val local = stack.repository.create(
                CreateMemoryInput("P-101 torque local", "Local says 40 Nm.", subjectKey = subject),
            )
            stack.cache.save(question, "Cloud says 52 Nm.", "cloud-hub")
            val conflictId = stack.database.conflictDao().listAll().single().conflictId

            val resolved = stack.resolver().keepLocal(conflictId)

            assertEquals(ConflictResolutionState.RESOLVED_LOCAL, resolved.state)
            assertEquals("Local says 40 Nm.", stack.repository.get(local.memoryId)?.content)
            assertEquals(1, stack.repository.list().size)
            assertEquals(0L, stack.database.syncOutboxDao().countAll())
        } finally {
            stack.close()
        }
    }

    @Test
    fun keepCloudResolutionActivatesIncomingAnswerAndTombstonesOldLocal() = runBlocking {
        val stack = newStack()
        try {
            val question = "What is the approved P-101 torque?"
            val subject = answerSubject(question)
            val local = stack.repository.create(
                CreateMemoryInput("P-101 torque local", "Local says 40 Nm.", subjectKey = subject),
            )
            stack.cache.save(question, "Cloud says 52 Nm.", "cloud-hub")
            val conflictRow = stack.database.conflictDao().listAll().single()

            val resolved = stack.resolver().keepCloud(conflictRow.conflictId)

            assertEquals(ConflictResolutionState.RESOLVED_CLOUD, resolved.state)
            val old = stack.database.memoryDao().getById(local.memoryId)
            assertEquals("old divergent local record becomes history", true, old?.tombstone)
            val incoming = stack.database.memoryDao().getById(conflictRow.incomingMemoryId!!)
            assertEquals("Cloud says 52 Nm.", incoming?.content)
            assertEquals("CLOUD", incoming?.origin)
            assertEquals("SYNCED", incoming?.syncState)
            assertEquals("cloud-hub", incoming?.authority)
            assertEquals("resolved cloud knowledge is never pushed back", 0L, stack.database.syncOutboxDao().countAll())
        } finally {
            stack.close()
        }
    }

    @Test
    fun localOnlyKnowledgeIsNeverOverwrittenOrUploadedBySave() = runBlocking {
        val stack = newStack()
        try {
            val question = "What is the gate code for site 7?"
            val subject = answerSubject(question)
            val local = stack.repository.create(
                CreateMemoryInput(
                    title = "Site access",
                    content = "Gate code for site 7 is 4412.",
                    subjectKey = subject,
                ),
            )
            assertEquals(
                SyncDecision.LOCAL_ONLY.name,
                stack.database.memoryDao().getById(local.memoryId)?.syncDecision,
            )

            val result = stack.cache.save(question, "Gate code for site 7 is 9999 per cloud.", "cloud-hub")

            assertTrue(result is CacheCloudAnswerResult.ConflictPrevented)
            assertEquals("Gate code for site 7 is 4412.", stack.repository.get(local.memoryId)?.content)
            assertEquals(1L, stack.database.conflictDao().countUnresolved())
            assertEquals(0L, stack.database.syncOutboxDao().countAll())
        } finally {
            stack.close()
        }
    }

    @Test
    fun identicalSubjectContentAndHashIsAlreadyPresentWithoutConflict() = runBlocking {
        val stack = newStack()
        try {
            val question = "What is the approved P-101 torque?"
            val subject = answerSubject(question)
            val answer = "The approved P-101 torque is 45 Nm."
            val cacheTitle = DefaultCloudAnswerCache.CLOUD_ANSWER_TITLE_PREFIX + question.trim().take(60)
            stack.repository.create(
                CreateMemoryInput(
                    title = cacheTitle,
                    content = answer,
                    subjectKey = subject,
                ),
            )

            val result = stack.cache.save(question, answer, "cloud-hub")

            assertSame(CacheCloudAnswerResult.AlreadyPresent, result)
            assertEquals("identical knowledge creates no conflict", 0L, stack.database.conflictDao().countUnresolved())
            assertEquals(1, stack.repository.list().size)
            assertEquals(0L, stack.database.syncOutboxDao().countAll())
        } finally {
            stack.close()
        }
    }

    @Test
    fun newerExistingCloudVersionIsNotRegressedByDivergentSave() = runBlocking {
        val stack = newStack()
        try {
            val question = "What is the approved P-101 torque?"
            val saved = stack.cache.save(question, "Torque is 50 Nm.", "cloud-hub")
            assertTrue(saved is CacheCloudAnswerResult.Saved)
            val first = (saved as CacheCloudAnswerResult.Saved).memory
            val bumped = stack.database.memoryDao().getById(first.memoryId)!!
            stack.database.memoryDao().update(bumped.copy(version = 5, contentHash = "hash-newer"))

            val result = stack.cache.save(question, "Cloud says 52 Nm.", "cloud-hub")

            assertTrue(result is CacheCloudAnswerResult.ConflictPrevented)
            val row = stack.database.memoryDao().getById(first.memoryId)!!
            assertEquals("existing newer cloud version is never regressed", 5, row.version)
            assertEquals("Torque is 50 Nm.", row.content)
            assertEquals(1L, stack.database.conflictDao().countUnresolved())
            assertEquals(0L, stack.database.syncOutboxDao().countAll())
        } finally {
            stack.close()
        }
    }

    @Test
    fun tombstonedRecordIsNotResurrectedBySave() = runBlocking {
        val stack = newStack()
        try {
            val question = "What is the approved P-101 torque?"
            val subject = answerSubject(question)
            val local = stack.repository.create(
                CreateMemoryInput("P-101 torque", "Local says 40 Nm.", subjectKey = subject),
            )
            val row = stack.database.memoryDao().getById(local.memoryId)!!
            stack.database.memoryDao().update(row.copy(tombstone = true))

            val result = stack.cache.save(question, "The approved P-101 torque is 45 Nm.", "cloud-hub")

            assertTrue(result is CacheCloudAnswerResult.Saved)
            assertEquals("tombstoned record stays dead", true, stack.database.memoryDao().getById(local.memoryId)?.tombstone)
            assertEquals("one new active record, not a resurrection", 1, stack.repository.list().size)
            assertEquals(0L, stack.database.syncOutboxDao().countAll())
        } finally {
            stack.close()
        }
    }

    @Test
    fun supersededTargetIsNotResurrectedOrOverwrittenBySave() = runBlocking {
        val stack = newStack()
        try {
            val question = "What is the approved P-101 torque?"
            val subject = answerSubject(question)
            val old = stack.repository.create(
                CreateMemoryInput("P-101 torque rev 3", "Torque is 40 Nm.", subjectKey = subject),
            )
            val newer = stack.repository.create(
                CreateMemoryInput("P-101 torque rev 4", "Torque is 45 Nm.", subjectKey = subject),
            )
            val newerRow = stack.database.memoryDao().getById(newer.memoryId)!!
            stack.database.memoryDao().update(newerRow.copy(supersedes = old.memoryId))
            assertEquals(listOf(old.memoryId), stack.database.memoryDao().supersededIds())

            val result = stack.cache.save(question, "Cloud says 52 Nm.", "cloud-hub")

            assertTrue(result is CacheCloudAnswerResult.ConflictPrevented)
            assertEquals("superseded target untouched", "Torque is 40 Nm.", stack.database.memoryDao().getById(old.memoryId)?.content)
            assertEquals("superseding record untouched", "Torque is 45 Nm.", stack.database.memoryDao().getById(newer.memoryId)?.content)
            assertEquals(listOf(old.memoryId), stack.database.memoryDao().supersededIds())
            assertEquals(1L, stack.database.conflictDao().countUnresolved())
            assertEquals(0L, stack.database.syncOutboxDao().countAll())
        } finally {
            stack.close()
        }
    }

    private class Stack(
        val database: EdgeMindDatabase,
        val store: LocalVectorStore,
        val repository: DefaultMemoryRepository,
        val cache: CloudAnswerCache,
        val embedding: EmbeddingService,
    ) {
        fun resolver() = DefaultConflictResolver(
            conflictDao = database.conflictDao(),
            memoryDao = database.memoryDao(),
            database = database,
            embeddingService = embedding,
            vectorStore = store,
        )

        fun close() {
            runBlocking { store.close() }
            database.close()
        }
    }

    private fun newStack(
        dbName: String = "phase8-cache-${UUID.randomUUID()}",
        qdrantDir: File = File(sandbox, "qdrant-${UUID.randomUUID()}"),
    ): Stack {
        val embedding = FeatureHashingEmbeddingService()
        val database = Room.databaseBuilder(context, EdgeMindDatabase::class.java, dbName).build()
        val store = QdrantEdgeVectorStore(qdrantDir)
        val repository = DefaultMemoryRepository(database.memoryDao(), store, embedding)
        val classifier = DefaultKnowledgeClassifier()
        val writer = DefaultCloudKnowledgeWriter(database.memoryDao(), database, store, embedding)
        val cache = DefaultCloudAnswerCache(classifier, writer, database.memoryDao(), database.conflictDao())
        return Stack(database, store, repository, cache, embedding)
    }

    private fun answerSubject(question: String): String {
        val normalized = QueryNormalizer.normalize(question)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return DefaultCloudAnswerCache.SUBJECT_PREFIX + digest.joinToString("") { "%02x".format(it) }
    }
}