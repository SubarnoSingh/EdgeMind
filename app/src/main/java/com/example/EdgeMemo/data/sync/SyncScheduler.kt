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

    /**
     * Phase 12B.12 — schedule one bounded execution of the Qdrant-native
     * pipeline under its OWN unique name (12A §25) so it can never interleave
     * with the legacy Room outbox job. Same CONNECTED constraint and
     * exponential backoff as the verified legacy scheduler — this is the
     * existing scheduler extended, not a competing one.
     *
     * `KEEP` (startup/resume path): if a run is already enqueued or executing,
     * it is left alone — durable state, not a fresh trigger, carries it.
     * `REPLACE` (explicit trigger): a user-requested sync supersedes a queued
     * idle run; a replaced execution is crash-safe by the engine's lease and
     * reconciliation design, never by cancellation timing.
     */
    fun requestQdrantSync(policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            QDRANT_SYNC_WORK_NAME,
            policy,
            buildQdrantRequest(),
        )
    }

    companion object {
        const val SYNC_WORK_NAME = "edgememo-sync"

        /** Qdrant-native pipeline unique work name (frozen in 12A §25). */
        const val QDRANT_SYNC_WORK_NAME = "edgemind-qdrant-sync"

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

        fun buildQdrantRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<QdrantSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()
    }
}