package com.example.EdgeMemo.presentation.record

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.data.document.ContentResolverDocumentReader
import com.example.EdgeMemo.domain.document.IngestDocumentUseCase
import com.example.EdgeMemo.domain.document.IngestionStage
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
    /** Document picked through the system file picker (DOCUMENT type only). */
    val documentUri: Uri? = null,
    /** Human-readable name of the picked document, shown in the form. */
    val documentName: String? = null,
    /** Real ingestion stage while a document is being processed. */
    val ingestionStage: IngestionStage? = null,
    /** Number of memories produced by a completed document ingestion. */
    val ingestedChunkCount: Int? = null,
    /** True when the record is being captured FOR a specific machine: the
     *  subject is locked to that machine's namespace and cannot be edited, so
     *  every initial record stays isolated to it. */
    val subjectLocked: Boolean = false,
    /** Human name of the machine the capture is locked to (header only). */
    val machineName: String? = null,
) {
    /** True while a DOCUMENT-type record is being extracted/chunked/embedded. */
    val isBusy: Boolean
        get() = submitting || ingestionStage == IngestionStage.EXTRACTING ||
            ingestionStage == IngestionStage.CHUNKING ||
            ingestionStage == IngestionStage.EMBEDDING ||
            ingestionStage == IngestionStage.STORING

    val isFormValid: Boolean
        get() {
            if (subject.trim().isEmpty()) return false
            return if (type == MemoryType.DOCUMENT) {
                documentUri != null || title.trim().isNotEmpty() || content.trim().isNotEmpty()
            } else {
                title.trim().isNotEmpty() || content.trim().isNotEmpty()
            }
        }
}

/**
 * ViewModel for creating a new record from the empty state.
 * Uses the existing production CreateMemoryUseCase path for typed records and
 * the existing document ingestion pipeline ([IngestDocumentUseCase] →
 * [com.example.EdgeMemo.domain.document.DocumentIngestionService]) for picked
 * documents — no new persistence or processing architecture.
 */
class CreateRecordViewModel(
    private val createMemory: CreateMemoryUseCase,
    private val navigator: EdgeNavigator,
    private val documentReader: ContentResolverDocumentReader? = null,
    private val ingestDocument: IngestDocumentUseCase? = null,
    initialSubject: String? = null,
    machineName: String? = null,
) : ViewModel() {

    private val locked = !initialSubject.isNullOrBlank()
    private val lockedSubject = initialSubject?.trim()?.lowercase()?.ifBlank { null }

    private val _uiState = MutableStateFlow(
        CreateRecordUiState(
            subject = initialSubject?.trim().orEmpty(),
            subjectLocked = locked,
            machineName = machineName?.trim()?.takeIf { it.isNotEmpty() },
            // Machine capture defaults to an observation; the type is editable.
            type = if (locked) MemoryType.OBSERVATION else MemoryType.NOTE,
        ),
    )
    val uiState: StateFlow<CreateRecordUiState> = _uiState.asStateFlow()

    /** MIME types the existing ingestion pipeline supports (PDF / TXT / MD). */
    val supportedDocumentMimeTypes: Array<String> =
        arrayOf("application/pdf", "text/plain", "text/markdown")

    fun onTitleChange(value: String) {
        _uiState.update { it.copy(title = value, error = null) }
    }

    fun onContentChange(value: String) {
        _uiState.update { it.copy(content = value, error = null) }
    }

    fun onSubjectChange(value: String) {
        if (locked) return // machine capture keeps the locked namespace
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
     * Receives the result of the system file picker. A `null` uri means the
     * user cancelled: the previous selection is kept and no error is shown.
     */
    fun onDocumentPicked(uri: Uri?) {
        if (uri == null) return
        _uiState.update { it.copy(documentUri = uri, documentName = null, error = null) }
        viewModelScope.launch {
            val reader = documentReader ?: return@launch
            runCatching { reader.resolve(uri) }
                .onSuccess { source ->
                    _uiState.update { it.copy(documentName = source.displayName) }
                }
                .onFailure {
                    _uiState.update { it.copy(error = "The selected document could not be read.") }
                }
        }
    }

    fun onDocumentClear() {
        _uiState.update { it.copy(documentUri = null, documentName = null, ingestionStage = null) }
    }

    /**
     * Creates the record through the production boundary. A DOCUMENT with a
     * picked file is passed through the existing ingestion pipeline (extract →
     * chunk → embed → Qdrant Edge + metadata); every other record type keeps
     * using `CreateMemoryUseCase` exactly as before.
     */
    fun submit() {
        val state = _uiState.value
        if (state.isBusy) return
        if (!state.isFormValid) {
            _uiState.update {
                it.copy(
                    error = if (state.type == MemoryType.DOCUMENT && state.documentUri == null) {
                        "Select a document, or provide a title or description."
                    } else {
                        "Subject/asset is required, and title or content must be provided."
                    },
                )
            }
            return
        }
        if (state.type == MemoryType.DOCUMENT && state.documentUri != null) {
            ingestSelectedDocument(state)
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

    /** Runs the picked document through the EXISTING ingestion pipeline. */
    private fun ingestSelectedDocument(state: CreateRecordUiState) {
        val reader = documentReader
        val ingest = ingestDocument
        val uri = state.documentUri
        if (reader == null || ingest == null || uri == null) {
            _uiState.update { it.copy(error = "Document ingestion is not available.") }
            return
        }
        _uiState.update { it.copy(submitting = true, error = null, ingestionStage = IngestionStage.SELECTING) }
        viewModelScope.launch {
            try {
                val source = reader.resolve(uri)
                val result = ingest(source, state.subject.trim().lowercase().ifBlank { null }) { stage ->
                    _uiState.update { it.copy(ingestionStage = stage) }
                }
                _uiState.update {
                    it.copy(
                        submitting = false,
                        ingestionStage = IngestionStage.COMPLETED,
                        ingestedChunkCount = result.chunkCount,
                        createdMemory = result.memories.firstOrNull(),
                        data = LoadableState.Ready(result.memories.firstOrNull()),
                    )
                }
            } catch (e: EdgeError) {
                _uiState.update {
                    it.copy(
                        submitting = false,
                        ingestionStage = IngestionStage.FAILED,
                        error = e.message ?: "the document could not be stored",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        submitting = false,
                        ingestionStage = IngestionStage.FAILED,
                        error = e.message ?: "the document could not be stored",
                    )
                }
            }
        }
    }

    /**
     * After successful creation, navigate to the new asset workspace. When this
     * capture was started from the Add Machine flow (locked), finishing pops the
     * composer and opens the new machine directly so its records are visible.
     */
    fun onCreatedAcknowledged() {
        val memory = _uiState.value.createdMemory
        _uiState.update { it.copy(createdMemory = null) }
        if (locked) {
            val target = lockedSubject
                ?: AssetModel.namespaceOf(memory?.subjectKey ?: "").ifBlank { null }
            target?.let { navigator.finishMachineCaptureOpenMachine(it) }
                ?: navigator.cancelMachineCapture()
            return
        }
        memory?.let {
            val namespace = AssetModel.namespaceOf(it.subjectKey ?: "")
            if (namespace.isNotBlank()) {
                navigator.openMachine(namespace)
            } else {
                navigator.pop()
            }
        }
    }

    /** Continue capturing without leaving the screen. */
    fun onAddAnother() {
        _uiState.update {
            it.copy(
                data = LoadableState.Ready(null),
                title = "",
                content = "",
                tags = "",
                documentUri = null,
                documentName = null,
                ingestionStage = null,
                ingestedChunkCount = null,
                createdMemory = null,
                submitting = false,
                error = null,
            )
        }
    }

    /** Cancel and return to previous screen (clears a machine-capture session). */
    fun cancel() {
        if (locked) navigator.cancelMachineCapture() else navigator.pop()
    }

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