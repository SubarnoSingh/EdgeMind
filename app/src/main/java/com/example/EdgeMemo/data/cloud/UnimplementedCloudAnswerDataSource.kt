package com.example.EdgeMemo.data.cloud

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.domain.cloud.CloudAnswer
import com.example.EdgeMemo.domain.cloud.CloudAnswerDataSource

/**
 * Honest Phase 8 remote: no cloud answer backend is configured, so escalation
 * reports [EdgeError.CloudUnavailable]. Never fabricates an answer.
 */
class UnimplementedCloudAnswerDataSource : CloudAnswerDataSource {
    override suspend fun ask(question: String): CloudAnswer {
        throw EdgeError.CloudUnavailable("no cloud answer backend is configured")
    }
}