package com.example.EdgeMemo.presentation.memory

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.RetrievedMemory
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.policy.PolicyDecision
import com.example.EdgeMemo.core.sync.SyncSummary
import com.example.EdgeMemo.domain.cloud.CloudPullResult
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.domain.document.IngestionStage

/** Honest cloud-pull reporting: an outcome or an explicit unavailability. */
sealed interface CloudPullStatus {
    data object Idle : CloudPullStatus

    /** The real pull outcome (applied / duplicates / conflicts / next cursor). */
    data class Success(val result: CloudPullResult) : CloudPullStatus

    /** No backend exists yet; nothing was fetched and nothing was faked. */
    data object Unavailable : CloudPullStatus

    data class Failed(val message: String) : CloudPullStatus
}

data class DocumentIngestionState(
    val stage: IngestionStage = IngestionStage.IDLE,
    val sourceName: String? = null,
    val chunkCount: Int = 0,
    val error: EdgeError? = null,
) {
    val isActive: Boolean
        get() = stage == IngestionStage.SELECTING ||
            stage == IngestionStage.EXTRACTING ||
            stage == IngestionStage.CHUNKING ||
            stage == IngestionStage.EMBEDDING ||
            stage == IngestionStage.STORING
}

data class MemoryUiState(
    val memories: List<Memory> = emptyList(),
    val results: List<RetrievedMemory> = emptyList(),
    val searchActive: Boolean = false,
    val query: String = "",
    val draftTitle: String = "",
    val draftContent: String = "",
    val draftType: MemoryType = MemoryType.NOTE,
    val draftUserChoice: SyncDecision? = null,
    val draftPolicy: PolicyDecision? = null,
    val isBusy: Boolean = false,
    val error: EdgeError? = null,
    val memoryCount: Long = 0L,
    val syncSummary: SyncSummary = SyncSummary(),
    val ingestion: DocumentIngestionState = DocumentIngestionState(),
    val unresolvedConflictCount: Long = 0L,
    val conflictsVisible: Boolean = false,
    val conflicts: List<Conflict> = emptyList(),
    val pullStatus: CloudPullStatus = CloudPullStatus.Idle,
) {
    val items: List<Memory>
        get() = if (searchActive) results.map { it.memory } else memories
}
