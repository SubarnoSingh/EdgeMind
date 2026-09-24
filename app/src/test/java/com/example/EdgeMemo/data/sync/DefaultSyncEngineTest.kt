package com.example.EdgeMemo.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.core.sync.OutboxOperationType
import com.example.EdgeMemo.core.sync.SyncFailureKind
import com.example.EdgeMemo.core.sync.SyncOperation
import com.example.EdgeMemo.core.sync.SyncPushResult
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.local.room.MemoryDao
import com.example.EdgeMemo.data.local.room.MemoryEntity
import com.example.EdgeMemo.data.local.room.SyncOutboxDao
import com.example.EdgeMemo.data.local.room.SyncOutboxEntity
import com.example.EdgeMemo.domain.sync.SyncRemoteDataSource
import com.example.EdgeMemo.testing.TestNativeLoader
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The durable outbox state machine over real Room: claim → push → ACK, failure
 * classification, retry/backoff-eligible transitions, crash recovery of stale
 * IN_FLIGHT rows, idempotent operation ids and single-winner concurrency.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DefaultSyncEngineTest {

    private lateinit var context: Context
    private lateinit var database: EdgeMindDatabase
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var memoryDao: MemoryDao
    private lateinit var remote: FakeRemote

    @Before
    fun setUp() {
        TestNativeLoader.ensureLoaded()
        context = ApplicationProvider.getApplicationContext()
        database = Room.databaseBuilder(context, EdgeMindDatabase::class.java, "engine-${UUID.randomUUID()}")
            .build()
        outboxDao = database.syncOutboxDao()
        memoryDao = database.memoryDao()
        remote = FakeRemote()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun successMarksAcknowledgedAndMemorySynced() = runBlocking {
        remote.results.add(SyncPushResult.Success)
        val engine = newEngine()
        val id = insertSyncableMemory()

        val run = engine.processPending()

        assertEquals(1, run.claimed)
        assertEquals(1, run.acked)
        assertEquals(0, run.deferredForRetry)
        assertEquals(0, run.remaining)
        assertEquals(OutboxOperationState.ACKED.name, outboxDao.getById("UPSERT-$id")!!.state)
        assertEquals("SYNCED", memoryDao.getById(id)!!.syncState)
        val ops = remote.received()
        assertEquals(1, ops.size)
        assertEquals("UPSERT-$id", ops.single().operationId)
    }

    @Test
    fun retryableFailureDeferredThenAcknowledgedOnRetry() = runBlocking {
        remote.results.add(SyncPushResult.Failure(SyncFailureKind.NETWORK))
        remote.results.add(SyncPushResult.Success)
        val engine = newEngine()
        val id = insertSyncableMemory()

        val first = engine.processPending()
        assertEquals(1, first.deferredForRetry)
        val op = outboxDao.getById("UPSERT-$id")!!
        assertEquals(OutboxOperationState.FAILED.name, op.state)
        assertEquals("failure kind id, never content", "NETWORK", op.lastError)
        assertTrue(first.remaining > 0)
        assertEquals("PENDING", memoryDao.getById(id)!!.syncState)

        val second = engine.processPending()
        assertEquals(1, second.acked)
        assertEquals(OutboxOperationState.ACKED.name, outboxDao.getById("UPSERT-$id")!!.state)
        assertEquals("SYNCED", memoryDao.getById(id)!!.syncState)
        assertEquals(2, remote.received().size)
    }

    @Test
    fun permanentFailureMovesToDeadAndMemoryFailed() = runBlocking {
        remote.results.add(SyncPushResult.Failure(SyncFailureKind.REJECTED))
        val engine = newEngine()
        val id = insertSyncableMemory()

        val run = engine.processPending()

        assertEquals(1, run.dead)
        assertEquals(OutboxOperationState.DEAD.name, outboxDao.getById("UPSERT-$id")!!.state)
        assertEquals("REJECTED", outboxDao.getById("UPSERT-$id")!!.lastError)
assertEquals("FAILED", memoryDao.getById(id)!!.syncState)
        assertEquals(0L, engine.summary().pending)
        assertEquals(1L, engine.summary().failed)
    }

    @Test
    fun retryBudgetExhaustionMovesToDeadWithoutFurtherPushes() = runBlocking {
        remote.alwaysFail = SyncFailureKind.NETWORK
        val engine = newEngine()
        val id = insertSyncableMemory()

        repeat(DefaultSyncEngine.MAX_ATTEMPTS) {
            engine.processPending()
        }
        // Attempts now == MAX (5); next processing exceeds the budget.
        engine.processPending()

        val op = outboxDao.getById("UPSERT-$id")!!
        assertEquals(OutboxOperationState.DEAD.name, op.state)
        assertEquals((DefaultSyncEngine.MAX_ATTEMPTS + 1).toLong(), op.attempts.toLong())
        assertEquals("FAILED", memoryDao.getById(id)!!.syncState)
        // one push per non-exhausted attempt only
        assertEquals(DefaultSyncEngine.MAX_ATTEMPTS.toLong(), remote.received().size.toLong())
        assertEquals(0L, engine.summary().pending)
        assertEquals(1L, engine.summary().failed)
    }

    @Test
    fun operationIdIsStableAcrossRetries() = runBlocking {
        remote.alwaysFail = SyncFailureKind.NETWORK
        val engine = newEngine()
        val id = insertSyncableMemory()

        repeat(3) { engine.processPending() }

        val ids = remote.received().map { it.operationId }.distinct()
        assertEquals(listOf("UPSERT-$id"), ids)
    }

    @Test
    fun crashRecoveryReappliesStaleInflightOperation() = runBlocking {
        remote.results.add(SyncPushResult.Success)
        val engine = newEngine()
        val id = insertSyncableMemory()

        // Simulate a worker that died after the remote succeeded but before
        // the local ACK: row sits IN_FLIGHT across a process boundary.
        outboxDao.claim("UPSERT-$id")

        val recovered = engine.processPending()

        assertTrue("stale IN_FLIGHT must be recovered and re-applied", recovered.acked == 1)
        assertEquals(OutboxOperationState.ACKED.name, outboxDao.getById("UPSERT-$id")!!.state)
        assertEquals("SYNCED", memoryDao.getById(id)!!.syncState)
        assertEquals("idempotent operation must reach the remote again", 1, remote.received().size)
    }

    @Test
    fun concurrentRunsNeverDoubleProcessAnOperation() = runBlocking {
        remote.results.add(SyncPushResult.Success)
        val engine = newEngine()
        val ids = insertSyncableMemories(10)

        // Slow the remote so multiple workers race through the claim window.
        remote.delayMillis = 50

        coroutineScope {
            (1..4).map {
                async { engine.processPending() }
            }.awaitAll()
        }

        assertEquals("all ten operations must be processed exactly once", 10, remote.received().size)
        assertEquals(10, remote.received().map { it.operationId }.distinct().size)
        ids.forEach { id ->
            assertEquals(OutboxOperationState.ACKED.name, outboxDao.getById("UPSERT-$id")!!.state)
        }
    }

    @Test
    fun summaryReflectsPersistedState() = runBlocking {
        remote.alwaysFail = SyncFailureKind.NETWORK
        val engine = newEngine()
        insertSyncableMemory()

        assertEquals(1L, engine.summary().pending)

        engine.processPending()

        assertEquals(0L, engine.summary().pending)
        assertEquals(1L, engine.summary().failed)
        assertEquals(0L, engine.summary().syncing)
        assertEquals(0L, engine.summary().synced)
    }

    private fun newEngine(): DefaultSyncEngine =
        DefaultSyncEngine(outboxDao, memoryDao, remote, database)

    private suspend fun insertSyncableMemory(): String {
        val id = "mem-${UUID.randomUUID()}"
        memoryDao.insert(memoryRow(id, "Procedure revision 4", "Procedure revision 4"))
        outboxDao.insertOrIgnore(
            SyncOutboxEntity(
                operationId = "UPSERT-$id",
                memoryId = id,
                operationType = OutboxOperationType.UPSERT.name,
                payloadTitle = "Procedure revision 4",
                payloadContent = "Procedure revision 4",
                createdAt = 1L,
                attempts = 0,
                state = OutboxOperationState.PENDING.name,
                lastError = null,
            ),
        )
        return id
    }

    private suspend fun insertSyncableMemories(count: Int): List<String> = (1..count).map { insertSyncableMemory() }

    private fun memoryRow(id: String, title: String, content: String) =
        MemoryEntity(
            memoryId = id,
            title = title,
            content = content,
            chunkId = null,
            source = "USER_ENTRY",
            type = "PROCEDURE",
            tags = emptyList(),
            createdAt = 1L,
            updatedAt = 1L,
            origin = "LOCAL",
            syncDecision = "SYNC",
            syncState = "PENDING",
            sensitivity = "STANDARD",
            importance = 0,
            version = 1,
            contentHash = "$id-hash",
            subjectKey = null,
            supersedes = null,
            tombstone = false,
            metadata = emptyMap(),
            policyReason = null,
            redactedTitle = null,
            redactedContent = null,
        )

    private class FakeRemote : SyncRemoteDataSource {
        val results: java.util.ArrayDeque<SyncPushResult> = java.util.ArrayDeque()
        var alwaysFail: SyncFailureKind? = null
        var delayMillis: Long = 0L
        private val received = ConcurrentLinkedQueue<SyncOperation>()

        override suspend fun push(operation: SyncOperation): SyncPushResult {
            if (delayMillis > 0) Thread.sleep(delayMillis)
            received.add(operation)
            alwaysFail?.let { return SyncPushResult.Failure(it) }
            val next = results.pollFirst()
            return next ?: SyncPushResult.Success
        }

        fun received(): List<SyncOperation> = received.toList()
    }
}