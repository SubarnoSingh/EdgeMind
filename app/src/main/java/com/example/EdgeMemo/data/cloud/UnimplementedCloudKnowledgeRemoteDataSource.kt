package com.example.EdgeMemo.data.cloud

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeBatch
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource

/**
 * Honest Phase 7 remote: there is no Qdrant Server / backend yet (a later
 * phase owns real cloud connectivity). Every pull reports the remote as
 * unavailable so the device never fabricates cloud knowledge. Test fixtures
 * implement the interface with deterministic items; they are fixtures, not a
 * substitute for a real endpoint.
 */
class UnimplementedCloudKnowledgeRemoteDataSource : CloudKnowledgeRemoteDataSource {
    override suspend fun pullKnowledge(cursor: String?): CloudKnowledgeBatch =
        throw EdgeError.CloudUnavailable("no cloud knowledge backend is configured")
}