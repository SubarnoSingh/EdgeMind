package com.example.EdgeMemo.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.local.room.SyncOutboxDao
import com.example.EdgeMemo.data.repository.DefaultMemoryRepository
import com.example.EdgeMemo.native.qdrant.LocalVectorStore
import com.example.EdgeMemo.native.qdrant.QdrantEdgeVectorStore
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 6 privacy integration tests: real Room + qdrant-edge + real policy +
 * real outbox writer + real engine. Every claim here is the boundary a
 * `LOCAL_ONLY` / `SYNC` / `SYNC_REDACTED` decision must enforce at rest and
 * across process restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OutboxPrivacyTest {

    private lateinit var context: Context
    private lateinit var sandbox: File

    @Before
    fun setUp() {
        TestNativeLoader.ensureLoaded()
        context = ApplicationProvider.getApplicationContext()
        sandbox = File(context.cacheDir, "phase6-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    @Test
    fun localOnlyMemoryProducesZeroOutboxRows() = runBlocking {
        val stack = newStack()
        try {
            val created = stack.repository.create(
                CreateMemoryInput("Site access", "Gate code for Site 7 is 4412"),
            )
            assertEquals(SyncDecision.LOCAL_ONLY, created.syncDecision)
            assertEquals(MemorySyncState.LOCAL, created.syncState)
            assertEquals(0L, stack.dao.countAll())
            assertEquals("the only copy lives locally", created.content, "Gate code for Site 7 is 4412")
            assertTrue(stack.repository.search("gate code site seven", 5).isNotEmpty())
        } finally {
            stack.close()
        }
    }

    @Test
    fun syncMemoryStoresAllowedOriginalInOutbox() = runBlocking {
        val stack = newStack()
        try {
            val created = stack.repository.create(
                CreateMemoryInput(
                    "Idea",
                    "Replace belt drive with a gear drive",
                    type = MemoryType.NOTE,
                    userSyncChoice = SyncDecision.SYNC,
                ),
            )
            assertEquals(SyncDecision.SYNC, created.syncDecision)
            assertEquals(MemorySyncState.PENDING, created.syncState)

            val op = stack.dao.getById("UPSERT-${created.memoryId}")
            assertNotNull(op)
            assertEquals(OutboxOperationState.PENDING.name, op!!.state)
            assertEquals("the allowed original is the payload", "Replace belt drive with a gear drive", op.payloadContent)
            assertEquals("stable deterministic idempotency key", "UPSERT-${created.memoryId}", op.operationId)
            assertNull(op.lastError)
        } finally {
            stack.close()
        }
    }

    @Test
    fun redactedMemoryStoresOnlyRedactedRepresentation() = runBlocking {
        val stack = newStack()
        try {
            val created = stack.repository.create(
                CreateMemoryInput("P-101 repair", "Replaced P-101 seal; root cause cavitation", type = MemoryType.REPAIR),
            )
            assertEquals(SyncDecision.SYNC_REDACTED, created.syncDecision)
            assertEquals(MemorySyncState.PENDING, created.syncState)

            val op = stack.dao.getById("UPSERT-${created.memoryId}")
            assertNotNull(op)
            assertEquals("original must remain local", "Replaced P-101 seal; root cause cavitation", created.content)
            assertTrue("newer secret identifiers must be redacted", op!!.payloadContent.contains("seal"))
            assertFalse("private identifier must never leave the device", op.payloadContent.contains("P-101"))
            assertFalse("private identifier must never leave the device", op.payloadTitle.contains("P-101"))
            assertNull(op.lastError)
        } finally {
            stack.close()
        }
    }

    @Test
    fun updateToLocalOnlyWithdrawsThePriorOperation() = runBlocking {
        val stack = newStack()
        try {
            val created = stack.repository.create(
                CreateMemoryInput(
                    "Idea",
                    "Replace belt drive with a gear drive",
                    type = MemoryType.NOTE,
                    userSyncChoice = SyncDecision.SYNC,
                ),
            )
            assertEquals(1L, stack.dao.countAll())

            val withdrawn = stack.repository.update(
                created.copy(content = "Gate code for Site 4 is 7731"),
            )
            assertEquals(SyncDecision.LOCAL_ONLY, withdrawn.syncDecision)
            assertEquals(MemorySyncState.LOCAL, withdrawn.syncState)
            assertEquals("a superseding LOCAL_ONLY decision removes the operation", 0L, stack.dao.countAll())
        } finally {
            stack.close()
        }
    }

    @Test
    fun repeatedUpdatesKeepOneRowWithLatestPayloadAndStableId() = runBlocking {
        val stack = newStack()
        try {
            val first = stack.repository.create(
                CreateMemoryInput(
                    "Procedure",
                    "Procedure revision 3",
                    type = MemoryType.PROCEDURE,
                ),
            )
            val second = stack.repository.update(first.copy(content = "Procedure revision 4"))
            val third = stack.repository.update(second.copy(content = "Procedure revision 5"))

            assertEquals(1L, stack.dao.countAll())
            val op = stack.dao.getById("UPSERT-${first.memoryId}")
            assertEquals("operationId must be stable across retries/re-enqueues", "UPSERT-${first.memoryId}", op!!.operationId)
            assertEquals("latest payload wins", "Procedure revision 5", op.payloadContent)
            assertEquals("re-enqueue resets to PENDING", OutboxOperationState.PENDING.name, op.state)
            assertEquals(SyncDecision.SYNC, third.syncDecision)
        } finally {
            stack.close()
        }
    }

    @Test
    fun outboxSurvivesProcessRestartWithSameRedactedPayload() = runBlocking {
        val dbName = "privacy-restart-${UUID.randomUUID()}"
        val qdrantDir = File(sandbox, "qdrant-${UUID.randomUUID()}")

        val first = newStack(dbName = dbName, qdrantDir = qdrantDir)
        val created = first.repository.create(
            CreateMemoryInput("P-101 repair", "P-101 seal replaced by Technician John", type = MemoryType.REPAIR),
        )
        val before = first.dao.getById("UPSERT-${created.memoryId}")
        first.close()

        val second = newStack(dbName = dbName, qdrantDir = qdrantDir)
        try {
            val after = second.dao.getById("UPSERT-${created.memoryId}")
            assertNotNull("the durable outbox must survive a restart", after)
            assertEquals(OutboxOperationState.PENDING.name, after!!.state)
            assertEquals("payload must not change across restarts", before!!.payloadContent, after.payloadContent)
            assertFalse(after.payloadContent.contains("P-101"))
            assertFalse(after.payloadTitle.contains("P-101"))
        } finally {
            second.close()
        }
    }

    @Test
    fun localDeleteCancelsPendingOperations() = runBlocking {
        val stack = newStack()
        try {
            val created = stack.repository.create(
                CreateMemoryInput(
                    "Procedure",
                    "Procedure revision 4",
                    type = MemoryType.PROCEDURE,
                ),
            )
            assertEquals(1L, stack.dao.countAll())

            stack.repository.delete(created.memoryId)
            assertEquals("delete must cancel the memory's operations", 0L, stack.dao.countAll())
        } finally {
            stack.close()
        }
    }

    private class Stack(
        val database: EdgeMindDatabase,
        val store: LocalVectorStore,
        val repository: DefaultMemoryRepository,
        val dao: SyncOutboxDao,
    ) {
        fun close() {
            runBlocking { store.close() }
            database.close()
        }
    }

    private fun newStack(
        dbName: String = "phase6-${UUID.randomUUID()}",
        qdrantDir: File = File(sandbox, "qdrant-${UUID.randomUUID()}"),
    ): Stack {
        val database = Room.databaseBuilder(context, EdgeMindDatabase::class.java, dbName).build()
        val store = QdrantEdgeVectorStore(qdrantDir)
        val writer = RoomSyncOutboxWriter(database, database.memoryDao(), database.syncOutboxDao())
        val repository = DefaultMemoryRepository(
            dao = database.memoryDao(),
            vectorStore = store,
            embeddingService = com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService(),
            outboxWriter = writer,
        )
        return Stack(database, store, repository, database.syncOutboxDao())
    }
}