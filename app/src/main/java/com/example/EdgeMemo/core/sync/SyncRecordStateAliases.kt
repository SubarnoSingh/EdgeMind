package com.example.EdgeMemo.core.sync

/** Top-level convenience wrappers for callers that prefer function syntax. */
fun isLegalQdrantRecordSyncTransition(
    from: QdrantRecordSyncState,
    to: QdrantRecordSyncState,
    currentVersion: Int,
    nextVersion: Int,
): Boolean = QdrantRecordSyncState.isLegalTransition(from, to, currentVersion, nextVersion)

fun validateQdrantRecordSyncTransition(
    from: QdrantRecordSyncState,
    to: QdrantRecordSyncState,
    currentVersion: Int,
    nextVersion: Int,
) = QdrantRecordSyncState.validateTransition(from, to, currentVersion, nextVersion)
