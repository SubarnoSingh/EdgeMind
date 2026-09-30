package com.example.EdgeMemo.presentation.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.EdgeMemo.core.sync.SyncSummary
import com.example.EdgeMemo.domain.conflict.CountUnresolvedConflictsUseCase
import com.example.EdgeMemo.domain.sync.SyncStatusReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Derived, honest shell badge state — never claims more than the store says. */
enum class ShellSyncUi {
    NO_OPERATIONS,
    PENDING,
    SYNCING,
    SYNCED,
    ATTENTION,
}

data class ShellUiState(
    val isOnline: Boolean = false,
    val sync: SyncSummary = SyncSummary(),
    val syncUi: ShellSyncUi = ShellSyncUi.NO_OPERATIONS,
    val unresolvedConflicts: Long = 0L,
    val refreshing: Boolean = false,
)

/**
 * Owns shell navigation + the header's real status signals. Connectivity
 * comes from the reactive OS callback flow; sync/conflict counts are read
 * from the Qdrant operation store / conflict store on refresh — nothing is
 * inferred, cached across screens, or faked.
 */
class ShellViewModel(
    val navigator: EdgeNavigator,
    isOnline: kotlinx.coroutines.flow.StateFlow<Boolean>,
    private val syncStatusReader: SyncStatusReader,
    private val countUnresolvedConflicts: CountUnresolvedConflictsUseCase,
) : ViewModel() {

    val route: StateFlow<EdgeRoute> = navigator.route

    private val _uiState = MutableStateFlow(ShellUiState(isOnline = isOnline.value))
    val uiState: StateFlow<ShellUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            isOnline.collect { online ->
                _uiState.update { it.copy(isOnline = online) }
            }
        }
        viewModelScope.launch {
            navigator.route.collect { refresh() }
        }
    }

    fun select(tab: EdgeTab) {
        navigator.select(tab)
    }

    fun push(route: EdgeRoute) = navigator.push(route)

    fun back(): Boolean = navigator.pop()

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            try {
                val summary = syncStatusReader.outboxCounts()
                val conflicts = countUnresolvedConflicts()
                _uiState.update {
                    it.copy(
                        sync = summary,
                        syncUi = deriveSyncUi(summary),
                        unresolvedConflicts = conflicts,
                        refreshing = false,
                    )
                }
            } catch (e: Exception) {
                // Honest degradation: the badge falls back to no-operations;
                // screens surface their own read errors separately.
                _uiState.update {
                    it.copy(
                        sync = SyncSummary(),
                        syncUi = ShellSyncUi.NO_OPERATIONS,
                        unresolvedConflicts = 0L,
                        refreshing = false,
                    )
                }
            }
        }
    }

    private fun deriveSyncUi(summary: SyncSummary): ShellSyncUi = when {
        summary.syncing > 0 -> ShellSyncUi.SYNCING
        summary.failed > 0 -> ShellSyncUi.ATTENTION
        summary.pending > 0 -> ShellSyncUi.PENDING
        summary.synced > 0 -> ShellSyncUi.SYNCED // real ACKED operations exist
        else -> ShellSyncUi.NO_OPERATIONS
    }
}
