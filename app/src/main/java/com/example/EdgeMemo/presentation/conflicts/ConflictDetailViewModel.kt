package com.example.EdgeMemo.presentation.conflicts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.domain.conflict.ConflictResolutionAction
import com.example.EdgeMemo.domain.conflict.ConflictResolutionState
import com.example.EdgeMemo.domain.conflict.GetConflictUseCase
import com.example.EdgeMemo.domain.conflict.ResolveConflictUseCase
import com.example.EdgeMemo.domain.memory.GetMemoryUseCase
import com.example.EdgeMemo.presentation.shell.EdgeNavigator
import com.example.EdgeMemo.presentation.shell.LoadableState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The confirmation stage + resolution lifecycle for one conflict. Kept
 * separate from the underlying [Conflict] so the UI state machine is explicit
 * and the domain object is never mutated locally.
 */
sealed interface ResolutionUi {
    data object Idle : ResolutionUi
    /** The user picked a side and must still confirm it. */
    data class Confirming(val action: ConflictResolutionAction) : ResolutionUi
    data object Resolving : ResolutionUi
    /**
     * After a real domain call + re-read. [wasAlreadyResolved] is derived from
     * the authoritative re-read (the resolver is idempotent), never a local
     * flag. [localRecord] is the real post-resolution local record (may be
     * null if the winning side was a tombstone / absent) and carries the
     * truthful sync state to display.
     */
    data class Resolved(
        val action: ConflictResolutionAction,
        val conflict: Conflict,
        val localRecord: Memory?,
        val wasAlreadyResolved: Boolean,
    ) : ResolutionUi

    data class Failed(val action: ConflictResolutionAction, val message: String) : ResolutionUi
}

/**
 * UI Phase 4 — conflict detail + human-driven resolution workflow.
 *
 * The ViewModel ONLY coordinates existing domain use cases
 * ([GetConflictUseCase] read, [ResolveConflictUseCase] the authoritative
 * resolver, [GetMemoryUseCase] for the truthful post-resolution sync state).
 * It contains no resolution business rules — version numbering, tombstone
 * safety, follow-up operations and immutability all live in the 12B.10
 * resolver. After any mutation it RE-READS the durable state; it never
 * assumes success.
 */
class ConflictDetailViewModel(
    private val conflictId: String,
    private val getConflict: GetConflictUseCase,
    private val resolveConflict: ResolveConflictUseCase,
    private val getMemory: GetMemoryUseCase,
    private val navigator: EdgeNavigator,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoadableState<ConflictDetailData>>(LoadableState.Loading)
    val uiState: StateFlow<LoadableState<ConflictDetailData>> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = LoadableState.Loading
            try {
                val conflict = getConflict(conflictId)
                _uiState.value = if (conflict == null) {
                    LoadableState.Failed("This conflict is no longer available.")
                } else {
                    LoadableState.Ready(ConflictDetailData(conflict, ResolutionUi.Idle))
                }
            } catch (e: Exception) {
                _uiState.value = LoadableState.Failed(
                    e.message ?: "the conflict could not be read",
                )
            }
        }
    }

    /** Re-read from the durable store — the honest retry path. */
    fun retry() = load()

    fun requestResolution(action: ConflictResolutionAction) {
        // Only from Idle / a prior Failure: never while resolving (double-tap
        // guard) and never silently switch a confirmation.
        _uiState.update { ready ->
            val data = (ready as? LoadableState.Ready)?.value ?: return@update ready
            if (data.resolution is ResolutionUi.Resolving) return@update ready
            ready.copy(value = data.copy(resolution = ResolutionUi.Confirming(action)))
        }
    }

    fun cancelResolution() {
        _uiState.update { ready ->
            val data = (ready as? LoadableState.Ready)?.value ?: return@update ready
            ready.copy(value = data.copy(resolution = ResolutionUi.Idle))
        }
    }

    /** The single entry point to a mutation. Guarded against re-entry. */
    fun confirmResolution() {
        val current = _uiState.value as? LoadableState.Ready ?: return
        val pending = (current.value.resolution as? ResolutionUi.Confirming)?.action ?: return
        if (current.value.resolution is ResolutionUi.Resolving) return

        // Enter Resolving synchronously so a second tap is impossible.
        _uiState.value = current.copy(
            value = current.value.copy(resolution = ResolutionUi.Resolving),
        )

        viewModelScope.launch {
            val before = runCatching { getConflict(conflictId) }.getOrNull()
            val resolution: ResolutionUi = try {
                resolveConflict(conflictId, pending)
                val after = getConflict(conflictId)
                if (after == null) {
                    ResolutionUi.Failed(pending, "The conflict could not be re-read after resolution.")
                } else {
                    ResolutionUi.Resolved(
                        action = pending,
                        conflict = after,
                        localRecord = after.localMemoryId?.let { runCatching { getMemory(it) }.getOrNull() },
                        wasAlreadyResolved = before != null &&
                            before.state != ConflictResolutionState.UNRESOLVED,
                    )
                }
            } catch (e: Exception) {
                ResolutionUi.Failed(pending, e.message ?: "the conflict could not be resolved")
            }
            val fresh = getConflict(conflictId)
            _uiState.update { state ->
                val data = (state as? LoadableState.Ready)?.value ?: return@update state
                state.copy(
                    value = data.copy(
                        conflict = fresh ?: data.conflict,
                        resolution = resolution,
                    ),
                )
            }
        }
    }

    fun back(): Boolean = navigator.pop()
}

/** One conflict plus the explicit resolution lifecycle overlaid on it. */
data class ConflictDetailData(
    val conflict: Conflict,
    val resolution: ResolutionUi,
) {
    val isUnresolved: Boolean
        get() = conflict.state == ConflictResolutionState.UNRESOLVED

    /** True once a real re-read shows the conflict left UNRESOLVED. */
    val isNowSettled: Boolean
        get() = conflict.state != ConflictResolutionState.UNRESOLVED
}
