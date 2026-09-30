package com.example.EdgeMemo.presentation.conflicts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.domain.conflict.CountUnresolvedConflictsUseCase
import com.example.EdgeMemo.domain.conflict.ListConflictsUseCase
import com.example.EdgeMemo.presentation.machines.AssetModel
import com.example.EdgeMemo.presentation.shell.EdgeNavigator
import com.example.EdgeMemo.presentation.shell.LoadableState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI Phase 4 — the unresolved-conflict workspace list. Reads ONLY through
 * the existing domain use cases (which the 13.4 Qdrant conflict store
 * implements); it knows nothing about records, shards or the resolver.
 */
data class ConflictRow(
    val conflict: Conflict,
    /** Asset namespace derived exactly like the asset view (real subjectKey). */
    val assetNamespace: String,
)

data class ConflictListData(
    /** Unresolved conflicts, detected-newest first (durable store order). */
    val rows: List<ConflictRow>,
    /** Real unresolved count from the conflict store. */
    val unresolvedCount: Long,
)

class ConflictListViewModel(
    private val listConflicts: ListConflictsUseCase,
    private val countUnresolvedConflicts: CountUnresolvedConflictsUseCase,
    private val navigator: EdgeNavigator,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoadableState<ConflictListData>>(LoadableState.Loading)
    val uiState: StateFlow<LoadableState<ConflictListData>> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = LoadableState.Loading
            try {
                val unresolved = listConflicts().filter {
                    it.state == com.example.EdgeMemo.domain.conflict.ConflictResolutionState.UNRESOLVED
                }
                val count = countUnresolvedConflicts()
                _uiState.value = LoadableState.Ready(
                    ConflictListData(
                        rows = unresolved
                            .sortedByDescending { it.detectedAt }
                            .map { conflict ->
                                ConflictRow(
                                    conflict = conflict,
                                    assetNamespace = conflict.subjectKey
                                        .takeIf { it.isNotBlank() }
                                        ?.let { AssetModel.namespaceOf(it) } ?: "unscoped",
                                )
                            },
                        unresolvedCount = count,
                    ),
                )
            } catch (e: Exception) {
                _uiState.value = LoadableState.Failed(
                    e.message ?: "conflict store could not be read",
                )
            }
        }
    }

    fun openConflict(conflictId: String) = navigator.openConflict(conflictId)

    fun back(): Boolean = navigator.pop()
}
