package com.example.EdgeMemo.core.sync

/** Sync state stored on a Qdrant-native knowledge record. */
enum class QdrantRecordSyncState {
    PENDING,
    SYNCED,
    CONFLICT,
    TOMBSTONED;

    companion object {
        /** Return whether the versioned state transition is permitted. */
        @JvmStatic
        fun isLegalTransition(
            from: QdrantRecordSyncState,
            to: QdrantRecordSyncState,
            currentVersion: Int,
            nextVersion: Int,
        ): Boolean = runCatching {
            validateTransition(from, to, currentVersion, nextVersion)
        }.isSuccess

        /**
         * Validate a state transition and its record-version relationship.
         *
         * State-only acknowledgements do not create a new record version.
         * Tombstoning and restoring do: both are versioned writes. Returning
         * from SYNCED to PENDING is also a new local write and therefore must
         * bump the record version.
         */
        @JvmStatic
        fun validateTransition(
            from: QdrantRecordSyncState,
            to: QdrantRecordSyncState,
            currentVersion: Int,
            nextVersion: Int,
        ) {
            require(currentVersion >= 1) { "Current record version must be at least 1" }
            require(nextVersion >= 1) { "Next record version must be at least 1" }
            require(nextVersion >= currentVersion) {
                "Record versions cannot move backwards"
            }

            if (from == to) {
                if (from == PENDING) {
                    require(nextVersion >= currentVersion) {
                        "A pending record cannot move to an older version"
                    }
                } else {
                    require(nextVersion == currentVersion) {
                        "A state-only transition cannot change the record version"
                    }
                }
                return
            }

            if (to == TOMBSTONED) {
                require(from != TOMBSTONED) { "Record is already tombstoned" }
                require(nextVersion == currentVersion + 1) {
                    "Tombstoning requires exactly one version bump"
                }
                return
            }

            if (from == TOMBSTONED) {
                require(nextVersion == currentVersion + 1) {
                    "Restoring a tombstoned record requires exactly one version bump"
                }
                return
            }

            if (from == QdrantRecordSyncState.SYNCED && to == PENDING) {
                require(nextVersion == currentVersion + 1) {
                    "SYNCED -> PENDING requires exactly one version bump"
                }
                return
            }

            if (to == QdrantRecordSyncState.SYNCED) {
                require(from == PENDING) {
                    "Only a pending record can become synced"
                }
                require(nextVersion == currentVersion) {
                    "Acknowledging a record must not change its version"
                }
                return
            }

            if (to == QdrantRecordSyncState.CONFLICT) {
                require(nextVersion == currentVersion) {
                    "Marking a record as conflicted does not itself change its version"
                }
                return
            }

            if (to == PENDING) {
                require(nextVersion == currentVersion + 1) {
                    "A transition to PENDING requires exactly one version bump"
                }
                return
            }

            throw IllegalArgumentException("Illegal Qdrant record sync transition: $from -> $to")
        }
    }
}

object QdrantRecordSyncStateTransitions {
    fun isLegal(
        from: QdrantRecordSyncState,
        to: QdrantRecordSyncState,
        currentVersion: Int,
        nextVersion: Int,
    ): Boolean = QdrantRecordSyncState.isLegalTransition(from, to, currentVersion, nextVersion)

    fun validate(
        from: QdrantRecordSyncState,
        to: QdrantRecordSyncState,
        currentVersion: Int,
        nextVersion: Int,
    ) = QdrantRecordSyncState.validateTransition(from, to, currentVersion, nextVersion)
}
