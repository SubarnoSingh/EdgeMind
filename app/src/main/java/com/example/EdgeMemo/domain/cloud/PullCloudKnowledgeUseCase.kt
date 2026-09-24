package com.example.EdgeMemo.domain.cloud

/** One manual "Pull cloud" action from the UI. */
class PullCloudKnowledgeUseCase(
    private val ingestor: CloudKnowledgeIngestor,
) {
    suspend operator fun invoke(): CloudPullResult = ingestor.pullAndApply()
}