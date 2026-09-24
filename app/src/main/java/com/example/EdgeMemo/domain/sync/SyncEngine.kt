package com.example.EdgeMemo.domain.sync

import com.example.EdgeMemo.core.sync.SyncSummary

/**
 * Orchestrates the durable outbox state machine. No policy logic lives here:
 * payloads are decided at persistence time by `SyncPayloadFactory`, and this
 * engine only moves already-sanctioned operations through
 * `PENDING → IN_FLIGHT → ACKED/FAILED/DEAD`.
 */
interface SyncEngine {

    /**
     * Drains up to [maxOperations] retryable operations.
     * Returns a summary of what actually happened. Never fakes an
     * acknowledgement: ACKED is only written after the remote returned
     * [com.example.EdgeMemo.core.sync.SyncPushResult.Success].
     */
    suspend fun processPending(maxOperations: Int = 25): SyncRunSummary

    /** Persisted counts for the UI (always real state). */
    suspend fun summary(): SyncSummary
}

data class SyncRunSummary(
    val claimed: Int = 0,
    val acked: Int = 0,
    val deferredForRetry: Int = 0,
    val dead: Int = 0,
    /** Operations still retryable after this run (worker should retry). */
    val remaining: Int = 0,
)