package com.example.EdgeMemo.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Requests durable, connectivity-gated sync work. Unique work + `REPLACE`
 * policy + drain semantics means only one worker is ever active, so duplicate
 * successful operations are structurally avoided at the scheduling layer;
 * per-row guarded claims additionally prevent double execution.
 */
class SyncScheduler(private val context: Context) {

    fun requestSync() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            SYNC_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            buildRequest(),
        )
    }

    companion object {
        const val SYNC_WORK_NAME = "edgememo-sync"

        /** Backoff delay before the first automatic retry. */
        const val BACKOFF_DELAY_SECONDS = 10L

        fun buildRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()
    }
}