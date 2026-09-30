package com.example.EdgeMemo.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.example.EdgeMemo.EdgeMindApplication
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.native.qdrant.QdrantNativeException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 12B.12 — WorkManager execution of the Qdrant-native sync pipeline.
 *
 * The worker is only the execution mechanism: all protocol, lease, cursor,
 * conflict and state-machine logic lives in the existing `QdrantSyncEngine`
 * stack (12B.7–12B.11). One execution performs, in order:
 *
 *  1. `reconcile()` — §19 bounded crash-recovery passes (R1–R5) over durable
 *     Qdrant state. Runs FIRST so work lost at any crash boundary is made
 *     claimable again before anything else touches the store.
 *  2. `pullAndApply()` — cloud → local page (§12). An unavailable cloud
 *     (`EdgeError.CloudUnavailable` — no backend configured OR transient
 *     HTTP failure, both wrapped by the production pull adapter) is skipped
 *     honestly; it never counts as cloud success.
 *  3. `resolveByAuthority()` — the automatic §16 authority-priority pass
 *     over conflict evidence recorded by the pull. Deterministic and
 *     idempotent; unresolved divergence stays visible.
 *  4. `pushPending()` — local → cloud drain (§11) of everything now
 *     claimable, including R2/R4 repairs, follow-up operations produced by
 *     authority resolution, and ordinary user writes.
 *
 * Result semantics (frozen with the legacy scheduler, 12A §25):
 * `retry()` while retryable operations remain or more cloud pages exist
 * (WorkManager backoff applies); `success()` only when the queue is drained;
 * DEAD operations never hold the queue open; a missing sync runtime is an
 * honest `failure()` — never a silent success.
 *
 * Bounded per invocation (PUSH/MAX constants) so a large backlog cannot
 * exceed the worker's execution budget. All state read or written is durable
 * Qdrant state; nothing depends on in-memory carryover between executions.
 */
class QdrantSyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val runtime: QdrantSyncRuntime?,
) : CoroutineWorker(appContext, params) {

    constructor(appContext: Context, params: WorkerParameters) : this(appContext, params, null)

    override suspend fun doWork(): ListenableWorker.Result = withContext(Dispatchers.IO) {
        val resolved = runtime
            ?: (applicationContext as? EdgeMindApplication)?.container?.qdrantSyncRuntime
            ?: return@withContext ListenableWorker.Result.failure()

        try {
            resolved.recordStore.ensureReady(resolved.dimension)
            resolved.recordStore.ensureIndexes()

            resolved.engine.reconcile()

            val pull = try {
                resolved.engine.pullAndApply(PULL_PAGE_SIZE)
            } catch (_: EdgeError.CloudUnavailable) {
                null
            }

            resolved.conflicts.resolveByAuthority(AUTO_RESOLVE_LIMIT)

            val push = resolved.engine.pushPending(MAX_OPERATIONS_PER_RUN)
            if (push.remaining > 0L || pull?.hasMore == true) {
                ListenableWorker.Result.retry()
            } else {
                ListenableWorker.Result.success()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (transient: EdgeError) {
            // Durable-store / cloud boundary failures are retry-safe BY DESIGN:
            // every transition is idempotent (deterministic identities, leases,
            // post-apply cursor ordering). Nothing was acknowledged here.
            ListenableWorker.Result.retry()
        } catch (transient: IOException) {
            ListenableWorker.Result.retry()
        } catch (transient: QdrantNativeException) {
            ListenableWorker.Result.retry()
        } catch (unexpected: Exception) {
            // Configuration / programming faults: fail, do not spin.
            ListenableWorker.Result.failure()
        }
    }

    companion object {
        /** Distinct unique-work name (12A §25) so the Qdrant-native pipeline
         *  can never interleave with the legacy Room outbox job. */
        const val WORK_NAME = "edgemind-qdrant-sync"

        /** Bounded drain per execution (legacy default batch size). */
        const val MAX_OPERATIONS_PER_RUN = 25

        /** One cloud page per execution; `hasMore` re-schedules via retry. */
        const val PULL_PAGE_SIZE = 50

        /** Bounded automatic authority-priority pass per execution. */
        const val AUTO_RESOLVE_LIMIT = 50
    }
}
