package com.example.EdgeMemo.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.EdgeMemo.core.sync.OutboxOperationState
import com.example.EdgeMemo.core.sync.OutboxOperationType
import com.example.EdgeMemo.core.sync.SyncFailureKind
import com.example.EdgeMemo.core.sync.SyncOperation
import com.example.EdgeMemo.core.sync.SyncPushResult
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.local.room.SyncOutboxDao
import com.example.EdgeMemo.data.local.room.SyncOutboxEntity
import com.example.EdgeMemo.domain.sync.SyncRemoteDataSource
import com.example.EdgeMemo.testing.TestNativeLoader
import java.util.UUID
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
 * Runs the real [SyncWorker] through WorkManager's [TestListenableWorkerBuilder]
 * over real Room. Verifies the worker drains the outbox and that its scheduling
 * request is connectivity-gated with exponential backoff.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncWorkerTest {

    private lateinit var context: Context
    private lateinit var database: EdgeMindDatabase
    private lateinit var outboxDao: SyncOutboxDao

    @Before
    fun setUp() {
        TestNativeLoader.ensureLoaded()
        context = ApplicationProvider.getApplicationContext()
        database = Room.databaseBuilder(context, EdgeMindDatabase::class.java, "worker-${UUID.randomUUID()}")
            .build()
        outboxDao = database.syncOutboxDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun workerDrainsAllPendingOperationsToSuccess() = runBlocking {
        val remote = RecordingRemote()
        remote.results.add(SyncPushResult.Success)
        insertOperation("mem-a", "UPSERT-mem-a")
        insertOperation("mem-b", "UPSERT-mem-b")
        val engine = DefaultSyncEngine(outboxDao, database.memoryDao(), remote, database)

        val result = buildWorker(engine).doWork()

        assertTrue("no retries needed when everything drains", result == ListenableWorker.Result.success())
        assertEquals(2, remote.received())
        assertEquals(
            listOf("ACKED", "ACKED"),
            listOfNotNull(
                outboxDao.getById("UPSERT-mem-a"),
                outboxDao.getById("UPSERT-mem-b"),
            ).map { it.state },
        )
    }

    @Test
    fun workerRequestsRetryWhenOperationsRemain() = runBlocking {
        val remote = RecordingRemote()
        remote.results.add(SyncPushResult.Success)
        remote.results.add(SyncPushResult.Failure(SyncFailureKind.NETWORK))
        insertOperation("mem-a", "UPSERT-mem-a")
        insertOperation("mem-b", "UPSERT-mem-b")
        val engine = DefaultSyncEngine(outboxDao, database.memoryDao(), remote, database)

        val result = buildWorker(engine).doWork()

        assertTrue("one op failed → worker must retry", result == ListenableWorker.Result.retry())
        assertEquals(2, remote.received())
        assertEquals(OutboxOperationState.ACKED.name, outboxDao.getById("UPSERT-mem-a")!!.state)
        assertEquals(OutboxOperationState.FAILED.name, outboxDao.getById("UPSERT-mem-b")!!.state)
    }

    @Test
    fun scheduledRequestIsConnectivityGatedWithExponentialBackoff() {
        val request = SyncScheduler.buildRequest()
        assertEquals(SyncWorker::class.java.name, request.workSpec.workerClassName)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(
            SyncScheduler.BACKOFF_DELAY_SECONDS * 1000L,
            request.workSpec.backoffDelayDuration,
        )
    }

    private suspend fun insertOperation(memoryId: String, operationId: String) {
        outboxDao.insertOrIgnore(
            SyncOutboxEntity(
                operationId = operationId,
                memoryId = memoryId,
                operationType = OutboxOperationType.UPSERT.name,
                payloadTitle = "Procedure revision 4",
                payloadContent = "Procedure revision 4",
                createdAt = System.currentTimeMillis(),
                attempts = 0,
                state = OutboxOperationState.PENDING.name,
                lastError = null,
            ),
        )
    }

    private fun buildWorker(engine: DefaultSyncEngine): SyncWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? =
                if (workerClassName == SyncWorker::class.java.name) {
                    SyncWorker(appContext, workerParameters, engine)
                } else {
                    null
                }
        }
        return TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(factory)
            .build()
    }

    private class RecordingRemote : SyncRemoteDataSource {
        val results: java.util.ArrayDeque<SyncPushResult> = java.util.ArrayDeque()
        private val received = java.util.concurrent.ConcurrentLinkedQueue<SyncOperation>()

        override suspend fun push(operation: SyncOperation): SyncPushResult {
            received.add(operation)
            return results.pollFirst() ?: SyncPushResult.Success
        }

        fun received(): Int = received.size
    }
}