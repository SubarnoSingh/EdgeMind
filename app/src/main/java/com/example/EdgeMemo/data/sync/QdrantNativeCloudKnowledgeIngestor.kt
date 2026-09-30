package com.example.EdgeMemo.data.sync

import com.example.EdgeMemo.data.local.sync.DefaultQdrantSyncEngine
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeIngestor
import com.example.EdgeMemo.domain.cloud.CloudPullResult

/**
 * Phase 13.4 — the ACTIVE cloud→edge pull: the existing
 * [DefaultQdrantSyncEngine.pullAndApply] (frozen 12B.9 pipeline) exposed
 * through the domain [CloudKnowledgeIngestor] boundary so the existing
 * `PullCloudKnowledgeUseCase` and UI trigger work UNCHANGED.
 *
 * This is an adapter, not a second implementation: validation, version
 * classification, tombstone handling, conflict recording, sys_cursor
 * durability, idempotent replay and echo immunity all live in the engine.
 * The legacy Room-backed `DefaultCloudKnowledgeIngestor` (with its
 * memoryDao/conflictDao/cloudCursorDao) is retired from the production graph
 * — its class and tests remain for rollback.
 */
class QdrantNativeCloudKnowledgeIngestor(
    private val engine: DefaultQdrantSyncEngine,
    private val pageSize: Int = 50,
) : CloudKnowledgeIngestor {

    override suspend fun pullAndApply(): CloudPullResult {
        val summary = engine.pullAndApply(pageSize)
        return CloudPullResult(
            applied = summary.applied,
            duplicates = summary.duplicates,
            conflicts = summary.conflicts,
            tombstoned = summary.tombstoned,
            noChange = summary.applied == 0 && summary.conflicts == 0 && summary.tombstoned == 0,
            cursor = summary.nextCursor,
        )
    }
}
