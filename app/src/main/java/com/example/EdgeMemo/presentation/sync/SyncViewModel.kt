package com.example.EdgeMemo.presentation.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.sync.SyncSummary
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.domain.conflict.ListConflictsUseCase
import com.example.EdgeMemo.domain.memory.ListMemoriesUseCase
import com.example.EdgeMemo.domain.sync.SyncStatusReader
import com.example.EdgeMemo.presentation.shell.LoadableState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Real synchronization picture: durable store counts + durable conflicts. */
data class SyncData(
    val summary: SyncSummary,
    val conflicts: List<Conflict>,
    val recentRecords: List<Memory>,
)

class SyncViewModel(
    private val syncStatusReader: SyncStatusReader,
    private val listConflicts: ListConflictsUseCase,
    private val listMemories: ListMemoriesUseCase,
    private val onSyncNow: () -> Unit,
    private val navigator: com.example.EdgeMemo.presentation.shell.EdgeNavigator? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoadableState<SyncData>>(LoadableState.Loading)
    val uiState: StateFlow<LoadableState<SyncData>> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = LoadableState.Loading
            try {
                val summary = syncStatusReader.outboxCounts()
                val conflicts = listConflicts()
                val recent = listMemories()
                    .sortedByDescending { it.updatedAt }
                    .take(RECENT_LIMIT)
                _uiState.value = LoadableState.Ready(SyncData(summary, conflicts, recent))
            } catch (e: Exception) {
                _uiState.value = LoadableState.Failed(
                    e.message ?: "synchronization state could not be read",
                )
            }
        }
    }

    /** Enqueues the real durable sync work (network-gated by WorkManager). */
    fun syncNow() {
        onSyncNow()
        refresh()
    }

    /** UI Phase 4 — a conflict row opens the evidence/resolution workflow. */
    fun openConflict(conflictId: String) {
        navigator?.openConflict(conflictId)
    }

    private companion object {
        const val RECENT_LIMIT = 8
    }
}
