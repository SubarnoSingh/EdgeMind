package com.example.EdgeMemo.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.example.EdgeMemo.EdgeMindApplication
import com.example.EdgeMemo.domain.sync.SyncEngine

/**
 * Durable sync worker. Schedules as a single unique one-shot work naming the
 * drain semantics: one worker runs, drains every retryable operation, and
 * asks for retry/backoff only while retryable operations remain (or the
 * remote is unavailable). No operation is ever acknowledged without a real
 * remote success.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val engine: SyncEngine?,
) : CoroutineWorker(appContext, params) {

    constructor(appContext: Context, params: WorkerParameters) : this(appContext, params, null)

    override suspend fun doWork(): ListenableWorker.Result {
        val resolved = engine ?: ((applicationContext as? EdgeMindApplication)?.container?.syncEngine)
            ?: return ListenableWorker.Result.failure()

        val summary = resolved.processPending()
        return if (summary.remaining > 0) ListenableWorker.Result.retry() else ListenableWorker.Result.success()
    }
}