package com.example.EdgeMemo.core.sync

import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordId

/** The result of comparing one incoming version with the current version. */
sealed interface SyncClassification {
    data object NEW : SyncClassification
    data object DUPLICATE : SyncClassification
    data object CONFLICT : SyncClassification
    data object STALE : SyncClassification
    data object UPDATE : SyncClassification

    companion object {
        /**
         * Apply the Phase 12 §7.1 matrix. Tombstones participate in exactly
         * the same version ordering as active records; they are not a second
         * operation-record kind and are never physically deleted here.
         */
        @JvmStatic
        fun classify(
            current: SyncRecordSnapshot?,
            incoming: SyncRecordSnapshot,
        ): SyncClassification {
            require(incoming.version >= 1) { "Incoming record version must be at least 1" }
            if (current == null) return NEW
            require(current.recordId == incoming.recordId) {
                "Cannot classify different logical record ids"
            }
            require(current.version >= 1) { "Current record version must be at least 1" }

            // A tombstone is a versioned state. An active record at the same
            // or an older version can never resurrect a current tombstone.
            if (current.tombstone && !incoming.tombstone && incoming.version <= current.version) {
                return STALE
            }
            // Creating a tombstone without a version bump is invalid; do not
            // turn that malformed state change into a duplicate.
            if (!current.tombstone && incoming.tombstone && incoming.version == current.version) {
                return CONFLICT
            }

            return when {
                incoming.version > current.version -> UPDATE
                incoming.version < current.version -> STALE
                incoming.contentHash == current.contentHash -> DUPLICATE
                else -> CONFLICT
            }
        }

        /** Classify existing Phase 11 records without changing their model. */
        @JvmStatic
        fun classify(
            current: Record?,
            incoming: Record,
        ): SyncClassification = classify(
            current = current?.toSnapshot(),
            incoming = incoming.toSnapshot(),
        )
    }
}

/** Minimal sync comparison view; it deliberately contains no storage concerns. */
data class SyncRecordSnapshot(
    val recordId: RecordId,
    val version: Int,
    val contentHash: String,
    val tombstone: Boolean = false,
) {
    init {
        require(version >= 1) { "Record version must be at least 1" }
        require(contentHash.isNotEmpty()) { "Content hash must not be empty" }
    }

    companion object {
        @JvmStatic
        fun fromString(
            recordId: String,
            version: Int,
            contentHash: String,
            tombstone: Boolean = false,
        ): SyncRecordSnapshot = SyncRecordSnapshot(
            RecordId.fromString(recordId),
            version,
            contentHash,
            tombstone,
        )
    }
}

/** Short alias for code that calls this a sync record rather than a snapshot. */
typealias SyncRecord = SyncRecordSnapshot

fun classifySyncRecord(
    current: SyncRecordSnapshot?,
    incoming: SyncRecordSnapshot,
): SyncClassification = SyncClassification.classify(current, incoming)

private fun Record.toSnapshot(): SyncRecordSnapshot = SyncRecordSnapshot(
    recordId = id,
    version = version,
    contentHash = contentHash ?: CanonicalContentHash.hash(payload),
    tombstone = tombstone,
)
