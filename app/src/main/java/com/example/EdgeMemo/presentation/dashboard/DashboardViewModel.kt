package com.example.EdgeMemo.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.sync.SyncSummary
import com.example.EdgeMemo.domain.conflict.CountUnresolvedConflictsUseCase
import com.example.EdgeMemo.domain.memory.ListMemoriesUseCase
import com.example.EdgeMemo.domain.sync.SyncStatusReader
import com.example.EdgeMemo.presentation.machines.AssetModel
import com.example.EdgeMemo.presentation.shell.EdgeNavigator
import com.example.EdgeMemo.presentation.shell.EdgeRoute
import com.example.EdgeMemo.presentation.shell.LoadableState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Everything the dashboard can honestly show. All figures are real reads:
 *  - record total = active knowledge records in the Qdrant shard
 *  - assets = subject-key namespaces present in those records
 *  - maintenance = REPAIR/OBSERVATION/EVENT-typed records (REPAIR/OBSERVATION/PROCEDURE-typed records
 *  - sync = Qdrant operation-store state; conflicts = conflict-store count
 * There is deliberately NO machine status/criticality/telemetry field: the
 * active domain exposes none (UI-phase rule: do not invent fields).
 */
data class DashboardData(
    val totalRecords: Int,
    val assetCount: Int,
    val maintenanceCount: Int,
    val unresolvedConflicts: Long,
    val sync: SyncSummary,
    val recentRecords: List<Memory>,
)

class DashboardViewModel(
    private val listMemories: ListMemoriesUseCase,
    private val syncStatusReader: SyncStatusReader,
    private val countUnresolvedConflicts: CountUnresolvedConflictsUseCase,
    private val navigator: EdgeNavigator,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoadableState<DashboardData>>(LoadableState.Loading)
    val uiState: StateFlow<LoadableState<DashboardData>> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = LoadableState.Loading
            try {
                val memories = listMemories()
                val sync = syncStatusReader.outboxCounts()
                val conflicts = countUnresolvedConflicts()
                val recent = memories
                    .sortedByDescending { it.updatedAt }
                    .take(RECENT_LIMIT)
                _uiState.value = LoadableState.Ready(
                    DashboardData(
                        totalRecords = memories.size,
                        assetCount = AssetModel.deriveAssets(memories).size,
                        maintenanceCount = memories.count {
                            it.type == MemoryType.REPAIR ||
                                it.type == MemoryType.OBSERVATION ||
                                it.type == MemoryType.PROCEDURE ||
                                it.type == MemoryType.EVENT
                        },
                        unresolvedConflicts = conflicts,
                        sync = sync,
                        recentRecords = recent,
                    ),
                )
            } catch (e: Exception) {
                _uiState.value = LoadableState.Failed(
                    e.message ?: "local memory could not be read",
                )
            }
        }
    }

    fun openMachines() = navigator.push(EdgeRoute.Machines)
    fun openRecords() = navigator.push(EdgeRoute.Records)
    fun openAsk() = navigator.select(com.example.EdgeMemo.presentation.shell.EdgeTab.ASK)
    fun openSync() = navigator.select(com.example.EdgeMemo.presentation.shell.EdgeTab.SYNC)
    fun openMachine(subjectKey: String) = navigator.openMachine(subjectKey)

    /** UI Phase 4 — the existing real unresolved-conflict metric opens the
     *  conflict workspace (no new analytics; navigation only). */
    fun openConflicts() = navigator.openConflicts()

    /** Open the generic record creation screen. */
    fun openCreateRecord() = navigator.openCreateRecord()

    private companion object {
        const val RECENT_LIMIT = 6
    }
}
