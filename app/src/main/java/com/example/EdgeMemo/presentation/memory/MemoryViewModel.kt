package com.example.EdgeMemo.presentation.memory

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.MemorySensitivity
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.policy.PolicyInput
import com.example.EdgeMemo.data.document.ContentResolverDocumentReader
import com.example.EdgeMemo.domain.cloud.PullCloudKnowledgeUseCase
import com.example.EdgeMemo.domain.conflict.ConflictResolutionAction
import com.example.EdgeMemo.domain.conflict.CountUnresolvedConflictsUseCase
import com.example.EdgeMemo.domain.conflict.ListConflictsUseCase
import com.example.EdgeMemo.domain.conflict.ResolveConflictUseCase
import com.example.EdgeMemo.domain.document.IngestDocumentUseCase
import com.example.EdgeMemo.domain.document.IngestionStage
import com.example.EdgeMemo.domain.memory.CreateMemoryUseCase
import com.example.EdgeMemo.domain.memory.DeleteMemoryUseCase
import com.example.EdgeMemo.domain.memory.ListMemoriesUseCase
import com.example.EdgeMemo.domain.memory.SearchMemoriesUseCase
import com.example.EdgeMemo.core.sync.SyncSummary
import com.example.EdgeMemo.domain.policy.PolicyEngine
import com.example.EdgeMemo.domain.sync.SyncOutboxWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MemoryViewModel(
    private val createMemory: CreateMemoryUseCase,
    private val listMemories: ListMemoriesUseCase,
    private val searchMemories: SearchMemoriesUseCase,
    private val deleteMemory: DeleteMemoryUseCase,
    private val documentReader: ContentResolverDocumentReader,
    private val ingestDocument: IngestDocumentUseCase,
    private val policyEngine: PolicyEngine,
    private val syncOutboxWriter: SyncOutboxWriter? = null,
    private val onMemoriesChanged: () -> Unit = {},
    private val pullCloudKnowledge: PullCloudKnowledgeUseCase? = null,
    private val listConflicts: ListConflictsUseCase? = null,
    private val countUnresolvedConflicts: CountUnresolvedConflictsUseCase? = null,
    private val resolveConflict: ResolveConflictUseCase? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun onTitleChange(value: String) {
        _uiState.update { it.copy(draftTitle = value) }
        recomputeDraftPolicy()
    }

    fun onContentChange(value: String) {
        _uiState.update { it.copy(draftContent = value) }
        recomputeDraftPolicy()
    }

    fun onTypeChange(value: MemoryType) {
        _uiState.update { it.copy(draftType = value) }
        recomputeDraftPolicy()
    }

    fun onUserSyncChoiceChange(value: SyncDecision?) {
        _uiState.update { it.copy(draftUserChoice = value) }
        recomputeDraftPolicy()
    }

    private fun recomputeDraftPolicy() {
        val state = state()
        if (state.draftTitle.isBlank() && state.draftContent.isBlank()) {
            _uiState.update { it.copy(draftPolicy = null) }
            return
        }
        val decision = policyEngine.evaluate(
            PolicyInput(
                title = state.draftTitle,
                content = state.draftContent,
                type = state.draftType,
                tags = emptyList(),
                sensitivity = MemorySensitivity.STANDARD,
                importance = 0,
                scope = null,
                userSyncChoice = state.draftUserChoice,
                metadata = emptyMap(),
            ),
        )
        _uiState.update { it.copy(draftPolicy = decision) }
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
    }

    fun create() {
        launchBusy {
            val state = state()
            createMemory(
                CreateMemoryInput(
                    title = state.draftTitle,
                    content = state.draftContent,
                    type = state.draftType,
                    userSyncChoice = state.draftUserChoice,
                ),
            )
            _uiState.update {
                it.copy(
                    draftTitle = "",
                    draftContent = "",
                    draftType = MemoryType.NOTE,
                    draftUserChoice = null,
                    draftPolicy = null,
                    searchActive = false,
                    results = emptyList(),
                )
            }
            onMemoriesChanged()
            reload()
        }
    }

    fun search() {
        launchBusy {
            val query = state().query.trim()
            if (query.isEmpty()) {
                clearSearch()
            } else {
                val results = searchMemories(query)
                _uiState.update { it.copy(results = results, searchActive = true) }
            }
        }
    }

    fun clearSearch() {
        _uiState.update { it.copy(query = "", results = emptyList(), searchActive = false) }
    }

    fun delete(memoryId: String) {
        launchBusy {
            deleteMemory(memoryId)
            onMemoriesChanged()
            reload()
        }
    }

    fun onDocumentPicked(uri: Uri?) {
        if (uri == null) {
            _uiState.update { it.copy(ingestion = DocumentIngestionState(stage = IngestionStage.IDLE)) }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    ingestion = DocumentIngestionState(stage = IngestionStage.SELECTING),
                    error = null,
                )
            }
            try {
                val source = documentReader.resolve(uri)
                _uiState.update {
                    it.copy(
                        ingestion = DocumentIngestionState(
                            stage = IngestionStage.EXTRACTING,
                            sourceName = source.displayName,
                        ),
                    )
                }
                val result = ingestDocument(source) { stage ->
                    _uiState.update { state ->
                        state.copy(ingestion = state.ingestion.copy(stage = stage))
                    }
                }
                _uiState.update {
                    it.copy(
                        ingestion = DocumentIngestionState(
                            stage = IngestionStage.COMPLETED,
                            sourceName = source.displayName,
                            chunkCount = result.chunkCount,
                        ),
                        searchActive = false,
                        results = emptyList(),
                    )
                }
                reload()
            } catch (e: EdgeError) {
                _uiState.update {
                    it.copy(ingestion = it.ingestion.copy(stage = IngestionStage.FAILED, error = e))
                }
            }
        }
    }

    fun dismissIngestion() {
        _uiState.update { it.copy(ingestion = DocumentIngestionState()) }
    }

    fun pullCloud() {
        val useCase = pullCloudKnowledge ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null, pullStatus = CloudPullStatus.Idle) }
            try {
                val result = useCase()
                _uiState.update { it.copy(pullStatus = CloudPullStatus.Success(result)) }
                reload()
            } catch (e: EdgeError.CloudUnavailable) {
                _uiState.update { it.copy(pullStatus = CloudPullStatus.Unavailable) }
            } catch (e: EdgeError) {
                _uiState.update { it.copy(pullStatus = CloudPullStatus.Failed(e.message ?: "cloud pull failed")) }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun toggleConflicts() {
        if (_uiState.value.conflictsVisible) {
            _uiState.update { it.copy(conflictsVisible = false, conflicts = emptyList()) }
        } else {
            viewModelScope.launch { loadConflicts() }
        }
    }

    fun resolve(conflictId: String, action: ConflictResolutionAction, note: String? = null) {
        val useCase = resolveConflict ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            try {
                useCase(conflictId, action, note)
                reload()
                loadConflicts()
            } catch (e: EdgeError) {
                _uiState.update { it.copy(error = e) }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        try {
            val memories = listMemories()
            val counts = syncOutboxWriter?.outboxCounts() ?: SyncSummary()
            val unresolved = countUnresolvedConflicts?.invoke() ?: 0L
            _uiState.update {
                val results = if (it.searchActive) {
                    it.results.filter { r -> memories.any { m -> m.memoryId == r.memory.memoryId } }
                } else {
                    emptyList()
                }
                it.copy(
                    memories = memories,
                    memoryCount = memories.size.toLong(),
                    results = results,
                    unresolvedConflictCount = unresolved,
                    syncSummary = counts.copy(
                        localOnly = memories.count { m -> m.syncDecision == SyncDecision.LOCAL_ONLY }
                            .toLong(),
                    ),
                )
            }
            if (_uiState.value.conflictsVisible) {
                loadConflicts()
            }
        } catch (e: EdgeError) {
            _uiState.update { it.copy(error = e) }
        }
    }

    private suspend fun loadConflicts() {
        val useCase = listConflicts ?: return
        val conflicts = useCase()
        _uiState.update { it.copy(conflicts = conflicts, conflictsVisible = true) }
    }

    fun consumeError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            try {
                block()
            } catch (e: EdgeError) {
                _uiState.update { it.copy(error = e) }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun state(): MemoryUiState = _uiState.value
}