package com.example.EdgeMemo.data.sync

import com.example.EdgeMemo.core.sync.SyncFailureKind
import com.example.EdgeMemo.core.sync.SyncOperation
import com.example.EdgeMemo.core.sync.SyncPushResult
import com.example.EdgeMemo.domain.sync.SyncRemoteDataSource

/**
 * Honest Phase 6 remote: there is no backend yet (spec Phase 7 owns Qdrant
 * Server connection). Every push reports `SOURCE_UNAVAILABLE` (retryable), so
 * operations stay durably pending and nothing is ever marked acknowledged
 * without a real remote. No cloud credentials live in this source code and no
 * arbitrary endpoint is contacted.
 */
class UnimplementedSyncRemoteDataSource : SyncRemoteDataSource {
    override suspend fun push(operation: SyncOperation): SyncPushResult =
        SyncPushResult.Failure(SyncFailureKind.SOURCE_UNAVAILABLE)
}