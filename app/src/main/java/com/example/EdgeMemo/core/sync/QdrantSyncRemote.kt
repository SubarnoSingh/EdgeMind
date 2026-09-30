package com.example.EdgeMemo.core.sync

/**
 * Machine-classifiable outcome of delivering one Phase 12 operation envelope
 * to the cloud. Maps 1:1 onto the frozen §23.1 backend contract:
 * `APPLIED | DUPLICATE | STALE | CONFLICT` plus transport failures.
 */
sealed interface SyncPushOutcome {

    /** Cloud accepted and persisted this operation (201/200 APPLIED). */
    data class Applied(
        val cloudVersion: Int?,
        val cloudContentHash: String?,
        val cloudTombstone: Boolean?,
    ) : SyncPushOutcome

    /** Cloud already holds exactly this operation (idempotent replay). */
    data class Duplicate(
        val cloudVersion: Int,
        val cloudContentHash: String,
    ) : SyncPushOutcome

    /** Cloud holds a NEWER version; the local intent is permanently invalid. */
    data class Stale(
        val cloudVersion: Int,
        val cloudContentHash: String,
        val cloudTombstone: Boolean?,
    ) : SyncPushOutcome

    /** Same version, different content hash: neither side auto-wins. */
    data class Conflict(
        val cloudVersion: Int,
        val cloudContentHash: String,
        val cloudTombstone: Boolean?,
    ) : SyncPushOutcome

    /** Transport failure that may succeed on retry (network / 429 / 5xx). */
    data class RetryableFailure(val kind: SyncFailureKind) : SyncPushOutcome

    /** Transport failure that can never succeed (401/403/400/422). */
    data class PermanentFailure(val kind: SyncFailureKind) : SyncPushOutcome
}

/**
 * The narrow cloud write boundary for the Qdrant-native Phase 12 path. The
 * production implementation speaks the existing `PUT /sync/operations/:id`
 * Phase 12 envelope contract (frozen in 12B.2/12B.3) — no parallel API is
 * introduced.
 */
interface QdrantSyncRemote {
    suspend fun push(operation: SyncOperationRecord): SyncPushOutcome
}
