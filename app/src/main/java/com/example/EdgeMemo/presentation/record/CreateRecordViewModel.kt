package com.example.EdgeMemo.presentation.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.domain.memory.CreateMemoryUseCase
import com.example.EdgeMemo.presentation.machines.AssetModel
import com.example.EdgeMemo.presentation.shell.EdgeNavigator
import com.example.EdgeMemo.presentation.shell.LoadableState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the generic record creation screen.
 */
data class CreateRecordUiState(
    /** Ready(null) = show empty form; Ready(memory) = show confirmation; Loading/Failed not used. */
    val data: LoadableState<Memory?> = LoadableState.Ready(null),
    val title: String = "",
    val content: String = "",
    val type: MemoryType = MemoryType.NOTE,
    val subject: String = "",
    val tags: String = "",
    /** null = let the policy engine decide; else explicit user choice. */
    val syncChoice: SyncDecision? = null,
    val submitting: Boolean = false,
    val error: String? = null,
    /** One-shot confirmation after successful creation. */
    val createdMemory: Memory? = null,
) {
    val isFormValid: Boolean
        get() = subject.trim().isNotEmpty() && (title.trim().isNotEmpty() || content.trim().isNotEmpty())
}

/**
 * ViewModel for creating a new record from the empty state.
 * Uses the existing production CreateMemoryUseCase path.
 */
class CreateRecordViewModel(
    private val createMemory: CreateMemoryUseCase,
    private val navigator: EdgeNavigator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateRecordUiState())
    val uiState: StateFlow<CreateRecordUiState> = _uiState.asStateFlow()

    fun onTitleChange(value: String) {
        _uiState.update { it.copy(title = value, error = null) }
    }

    fun onContentChange(value: String) {
        _uiState.update { it.copy(content = value, error = null) }
    }

    fun onSubjectChange(value: String) {
        _uiState.update { it.copy(subject = value, error = null) }
    }

    fun onTagsChange(value: String) {
        _uiState.update { it.copy(tags = value) }
    }

    fun onTypeChange(value: MemoryType) {
        _uiState.update { it.copy(type = value, error = null) }
    }

    fun onSyncChoiceChange(value: SyncDecision?) {
        _uiState.update { it.copy(syncChoice = value, error = null) }
    }

    /**
     * Creates the record through the production CreateMemoryUseCase.
     * Subject key is derived from the user-entered subject/asset identifier.
     */
    fun submit() {
        val state = _uiState.value
        if (state.submitting) return
        if (!state.isFormValid) {
            _uiState.update { it.copy(error = "Subject/asset is required, and title or content must be provided.") }
            return
        }
        _uiState.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val subjectKey = buildSubjectKey(state.subject.trim(), state.type)
                val created = createMemory(
                    CreateMemoryInput(
                        title = state.title.trim(),
                        content = state.content.trim(),
                        type = state.type,
                        tags = state.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        subjectKey = subjectKey,
                        userSyncChoice = state.syncChoice,
                    ),
                )
                _uiState.update {
                    it.copy(
                        submitting = false,
                        createdMemory = created,
                        data = LoadableState.Ready(created),
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        submitting = false,
                        error = e.message ?: "the record could not be stored",
                    )
                }
            }
        }
    }

    /**
     * After successful creation, navigate to the new asset workspace.
     */
    fun onCreatedAcknowledged() {
        val memory = _uiState.value.createdMemory
        _uiState.update { it.copy(createdMemory = null) }
        memory?.let {
            val namespace = AssetModel.namespaceOf(it.subjectKey ?: "")
            if (namespace.isNotBlank()) {
                navigator.openMachine(namespace)
            } else {
                navigator.pop()
            }
        }
    }

    /** Cancel and return to previous screen. */
    fun cancel() = navigator.pop()

    private fun buildSubjectKey(subject: String, type: MemoryType): String {
        val normalizedSubject = subject.trim().lowercase()
        val typeSegment = type.name.lowercase()
        return "$normalizedSubject/$typeSegment"
    }
}

/**
 * Presentation-only asset namespace derivation, mirroring AssetModel.namespaceOf.
 * Kept here to avoid importing MachineDetailViewModel's AssetModel from the record package.
 */
object CreateRecordModel {
    fun namespaceOf(subjectKey: String): String =
        subjectKey.substringBefore('/').trim().lowercase().ifEmpty { subjectKey.trim().lowercase() }
}