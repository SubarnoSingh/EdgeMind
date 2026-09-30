package com.example.EdgeMemo.data.sync

import com.example.EdgeMemo.core.sync.QdrantSyncRemote
import com.example.EdgeMemo.core.sync.SyncFailureKind
import com.example.EdgeMemo.core.sync.SyncOperationRecord
import com.example.EdgeMemo.core.sync.SyncPushOutcome

/**
 * Honest Qdrant-native cloud remote for builds with no backend configured
 * (blank `CLOUD_BACKEND_URL`). Mirrors the VERIFIED legacy
 * `UnimplementedSyncRemoteDataSource`: every push reports a retryable
 * `SOURCE_UNAVAILABLE` failure, so operations stay durably pending in the
 * Qdrant-native store and nothing is ever acknowledged without a real
 * remote success. No cloud credentials live in source; no endpoint is
 * invented.
 *
 * LOCAL_ONLY is additionally refused by construction (defense in depth —
 * the change detector already never sanctions such operations).
 */
class UnimplementedQdrantSyncRemote : QdrantSyncRemote {
    override suspend fun push(operation: SyncOperationRecord): SyncPushOutcome {
        require(operation.syncDecision != com.example.EdgeMemo.core.record.SyncDecision.LOCAL_ONLY) {
            "LOCAL_ONLY operations must never be pushed"
        }
        return SyncPushOutcome.RetryableFailure(SyncFailureKind.SOURCE_UNAVAILABLE)
    }
}
