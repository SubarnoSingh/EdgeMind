package com.example.EdgeMemo.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.example.EdgeMemo.domain.sync.SyncEngine

/**
 * LEGACY Room-outbox sync worker — RETIRED from the production graph by
 * Phase 13.4. Nothing schedules this worker (SyncScheduler.requestSync has
 * no callers) and the container no longer exposes the Room sync engine, so
 * it can never execute alongside [QdrantSyncWorker]. The class and its
 * tests are preserved untouched for rollback. The ACTIVE sync path is the
 * Qdrant-native worker ("edgemind-qdrant-sync").
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val engine: SyncEngine?,
) : CoroutineWorker(appContext, params) {

    constructor(appContext: Context, params: WorkerParameters) : this(appContext, params, null)

    override suspend fun doWork(): ListenableWorker.Result {
        // Phase 13.4: no container fallback — the legacy Room outbox is not
        // wired into the production graph anymore. Without an explicitly
        // injected (rollback) engine this worker honestly does nothing.
        val resolved = engine ?: return ListenableWorker.Result.failure()

        val summary = resolved.processPending()
        return if (summary.remaining > 0) ListenableWorker.Result.retry() else ListenableWorker.Result.success()
    }
}