package com.example.EdgeMemo.core.sync

/**
 * Outcome of running Phase 12 change detection over one local record.
 *
 * Detection NEVER sends anything to the cloud (that is 12B.8); its only side
 * effect, when a change is real, is a deterministic operation record in the
 * Qdrant-native [SyncOperationStore].
 */
sealed interface ChangeDetectionOutcome {

    /** A new PENDING operation was created for this record version. */
    data class Enqueued(val operation: SyncOperationRecord) : ChangeDetectionOutcome

    /**
     * The operation identity for this (record, version) already exists; the
     * stored operation is returned unchanged. Repeated detection is therefore
     * a no-op (idempotent).
     */
    data class AlreadyEnqueued(val operation: SyncOperationRecord) : ChangeDetectionOutcome

    /**
     * Nothing to synchronize. [classification] explains why:
     * `DUPLICATE` (same version + same canonical hash), `STALE` (version at
     * or below the cloud watermark), or `CONFLICT` (same version, different
     * hash — never auto-resolved here).
     */
    data class NoChange(val classification: SyncClassification) : ChangeDetectionOutcome

    /** LOCAL_ONLY content: policy forbids any operation record, ever. */
    data object LocalOnlyProtected : ChangeDetectionOutcome

    /**
     * The record arrived from the cloud. Pushing it back would be an echo
     * (§13); cloud-origin records never generate outgoing operations.
     */
    data object CloudOriginIgnored : ChangeDetectionOutcome

    /**
     * A `SYNC_REDACTED` record has no stored redacted representation. The
     * raw content must NOT be enqueued (privacy boundary); this is reported
     * honestly instead of silently syncing the original.
     */
    data object RedactionUnavailable : ChangeDetectionOutcome
}

/**
 * Deterministic classification of one local write against the record's
 * synchronization watermark. Mirrors the frozen §7.1 matrix; integer versions
 * are ordering metadata only — the content hash carries the identity claim.
 */
enum class LocalWriteClassification {
    NEW,
    UPDATE,
    DUPLICATE,
    STALE,
    CONFLICT,
}
