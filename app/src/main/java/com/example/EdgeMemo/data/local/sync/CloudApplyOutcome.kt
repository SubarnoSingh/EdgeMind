package com.example.EdgeMemo.data.local.sync

import com.example.EdgeMemo.core.record.Record

/**
 * Phase 13.4 — outcome of applying ONE cloud item through the engine's frozen
 * §12 pipeline (validation → classification → idempotent apply → conflict
 * recording). Shared by a pulled page ([DefaultQdrantSyncEngine.pullAndApply])
 * and the single-item answer-cache path
 * ([DefaultQdrantSyncEngine.applyCloudItem]) so there is exactly ONE
 * cloud→local application implementation — no duplicate pull semantics, no
 * bypassed validation.
 *
 * The variants map 1:1 onto the classification matrix:
 *  - [Applied]  — NEW or UPDATE, durably written (tombstone participates
 *    identically; `update` records whether a local record already existed).
 *  - [Duplicate] — same (id, version, hash): no write (§13 echo).
 *  - [Stale]    — local is ahead: cloud never overwrites.
 *  - [Conflict] — divergent content at incompatible state: durable evidence
 *    recorded, local untouched (12B.10 resolves).
 *  - [RejectedInvalid] — malformed item (non-UUID id, version < 1 or a
 *    non-canonical hash): refused BEFORE any local insert (§26).
 */
sealed interface CloudApplyOutcome {
    data class Applied(val record: Record, val update: Boolean) : CloudApplyOutcome
    data object Duplicate : CloudApplyOutcome
    data object Stale : CloudApplyOutcome
    data class Conflict(val conflict: Record) : CloudApplyOutcome
    data object RejectedInvalid : CloudApplyOutcome
}
