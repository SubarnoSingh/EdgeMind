package com.example.EdgeMemo.core.sync

import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision

/**
 * Durable outbox operation lifecycle. Transitions are driven by the sync
 * worker only; nothing fakes an acknowledgement.
 */
enum class OutboxOperationState {
    /** Created while offline (or retryable failure) — waits for connectivity. */
    PENDING,

    /** Claimed by a sync worker; one claim wins via a guarded update. */
    IN_FLIGHT,

    /** The remote actually acknowledged the operation. */
    ACKED,

    /** Failed after a retryable error; eligible for another retry. */
    FAILED,

    /** Permanent failure or retry budget exhausted — no further automatic retries. */
    DEAD,
}

enum class OutboxOperationType {
    UPSERT,
}

/**
 * Classification of a remote push outcome. [lastError] columns and log lines
 * MUST only ever carry these classification ids or a non-sensitive message —
 * never raw memory content.
 */
enum class SyncFailureKind {
    /** No backend is configured/implemented yet (Phase 7). Honest no-op. */
    SOURCE_UNAVAILABLE,

    /** Connectivity or timeout; safe to retry. */
    NETWORK,

    /** Transient server-side error; safe to retry. */
    SERVER_TEMPORARY,

    /** Server rejected the payload permanently. */
    REJECTED,

    /** Server rejected for authorization reasons. */
    UNAUTHORIZED,
}

sealed interface SyncPushResult {
    data object Success : SyncPushResult

    data class Failure(val kind: SyncFailureKind) : SyncPushResult
}

/**
 * A prepared operation the worker hands to the remote data source.
 *
 * [title]/[content] are the policy-sanctioned payload (SyncPayloadFactory);
 * the remaining fields are enriched at push time from the Room memory row so
 * a real backend can store the full evolving-memory schema. LOCAL_ONLY
 * memories can never appear here (they produce no outbox rows).
 */
data class SyncOperation(
    val operationId: String,
    val memoryId: String,
    val operationType: OutboxOperationType,
    val title: String,
    val content: String,
    val syncDecision: SyncDecision = SyncDecision.SYNC,
    val origin: MemoryOrigin = MemoryOrigin.LOCAL,
    val redacted: Boolean = false,
    val version: Int = 1,
    val contentHash: String = "",
    val subjectKey: String? = null,
    val type: MemoryType = MemoryType.NOTE,
    val tags: List<String> = emptyList(),
    val chunkId: String? = null,
    val source: String = "USER_ENTRY",
    val supersedes: String? = null,
    val tombstone: Boolean = false,
    val updatedAt: Long = 0L,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Read-only, aggregated, persisted sync state for the UI. Every number comes
 * from the outbox table or the current memory list — no fabricated metrics.
 */
data class SyncSummary(
    val localOnly: Long = 0L,
    val pending: Long = 0L,
    val syncing: Long = 0L,
    val synced: Long = 0L,
    val failed: Long = 0L,
) {
    val total: Long
        get() = pending + syncing + synced + failed
}