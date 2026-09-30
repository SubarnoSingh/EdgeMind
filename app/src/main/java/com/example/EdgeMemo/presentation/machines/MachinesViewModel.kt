package com.example.EdgeMemo.presentation.machines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.domain.conflict.ListConflictsUseCase
import com.example.EdgeMemo.domain.memory.CreateMemoryUseCase
import com.example.EdgeMemo.domain.memory.GetMemoryUseCase
import com.example.EdgeMemo.domain.memory.ListMemoriesUseCase
import com.example.EdgeMemo.presentation.machines.AssetModel.Asset
import com.example.EdgeMemo.presentation.shell.EdgeNavigator
import com.example.EdgeMemo.presentation.shell.EdgeRoute
import com.example.EdgeMemo.presentation.shell.LoadableState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MachinesViewModel(
    private val listMemories: ListMemoriesUseCase,
    private val listConflicts: ListConflictsUseCase,
    val navigator: EdgeNavigator,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoadableState<List<Asset>>>(LoadableState.Loading)
    val uiState: StateFlow<LoadableState<List<Asset>>> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = LoadableState.Loading
            try {
                val memories = listMemories()
                val conflictsByNamespace = listConflicts()
                    .asSequence()
                    .mapNotNull { it.subjectKey.takeIf(String::isNotBlank) }
                    .groupingBy { AssetModel.namespaceOf(it) }
                    .eachCount()
                    .toMap()
                    .mapValues { it.value.toLong() }
                _uiState.value = LoadableState.Ready(
                    AssetModel.deriveAssets(memories, conflictsByNamespace),
                )
            } catch (e: Exception) {
                _uiState.value = LoadableState.Failed(
                    e.message ?: "asset records could not be read",
                )
            }
        }
    }

    fun openAsset(namespace: String) = navigator.openMachine(namespace)
}

/** Detail data for one derived asset: real records only. */
data class MachineDetailData(
    val asset: Asset,
    /** Active asset records in deterministic UI Phase 5 activity order. */
    val records: List<Memory>,
    val conflictCount: Long,
    /** Unresolved conflicts for this asset namespace from the domain store. */
    val conflicts: List<com.example.EdgeMemo.domain.conflict.Conflict> = emptyList(),
) {
    val countsByCategory: Map<AssetModel.AssetRecordCategory, Int> =
        AssetModel.categoryCounts(records)
    val unresolvedConflictCount: Long = conflictCount
    val pendingSyncRecords: Int = records.count {
        it.syncState == com.example.EdgeMemo.core.model.MemorySyncState.PENDING
    }
    val failedSyncRecords: Int = records.count {
        it.syncState == com.example.EdgeMemo.core.model.MemorySyncState.FAILED
    }

    /** Focused sections are projections of the existing MemoryType mapping. */
    val maintenanceRecords: List<Memory> =
        AssetModel.recordsForCategory(records, AssetModel.AssetRecordCategory.MAINTENANCE)
    val observationRecords: List<Memory> =
        AssetModel.recordsForCategory(records, AssetModel.AssetRecordCategory.OBSERVATIONS)
    val incidentRecords: List<Memory> =
        AssetModel.recordsForCategory(records, AssetModel.AssetRecordCategory.INCIDENTS)
    val procedureRecords: List<Memory> =
        AssetModel.recordsForCategory(records, AssetModel.AssetRecordCategory.PROCEDURES)
    val documentRecords: List<Memory> =
        AssetModel.recordsForCategory(records, AssetModel.AssetRecordCategory.DOCUMENTS)

    /**
     * Conflict indicators are joined once in memory from the already-loaded
     * conflict set. No per-row repository calls or duplicated conflict state.
     */
    val conflictsByMemoryId: Map<String, List<com.example.EdgeMemo.domain.conflict.Conflict>> =
        conflicts
            .flatMap { conflict ->
                listOfNotNull(conflict.localMemoryId, conflict.incomingMemoryId)
                    .distinct()
                    .map { memoryId -> memoryId to conflict }
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .mapValues { (_, matches) ->
                matches.sortedWith(
                    compareByDescending<com.example.EdgeMemo.domain.conflict.Conflict> { it.detectedAt }
                        .thenBy { it.conflictId },
                )
            }

    fun conflictsFor(memoryId: String): List<com.example.EdgeMemo.domain.conflict.Conflict> =
        conflictsByMemoryId[memoryId].orEmpty()
}

/** Composer state for the asset-scoped "add record" flow (real use case). */
data class AssetComposerState(
    val open: Boolean = false,
    val title: String = "",
    val content: String = "",
    val type: MemoryType = MemoryType.OBSERVATION,
    /** null = let the policy engine decide; else explicit user choice. */
    val syncChoice: com.example.EdgeMemo.core.model.SyncDecision? = null,
    val submitting: Boolean = false,
    val error: String? = null,
    /** Honest one-shot confirmation after a successful creation. */
    val createdMessage: String? = null,
    /** Sync state returned by the real production create call. */
    val createdSyncState: com.example.EdgeMemo.core.model.MemorySyncState? = null,
)

/**
 * UI Phase 3 — the asset workspace state. One immutable flow; every field
 * either comes from the domain or is explicit transient UI input (composer).
 * No machine attributes exist beyond what records actually carry.
 */
data class AssetDetailUiState(
    val data: LoadableState<MachineDetailData> = LoadableState.Loading,
    /** Client-side category filter over the ALREADY-loaded bounded records. */
    val categoryFilter: AssetModel.AssetRecordCategory? = null,
    val composer: AssetComposerState = AssetComposerState(),
) {
    val visibleRecords: List<Memory>
        get() {
            val value = (data as? LoadableState.Ready)?.value ?: return emptyList()
            return if (categoryFilter == null) value.records
            else value.records.filter { AssetModel.categoryOf(it.type) == categoryFilter }
        }
}

class MachineDetailViewModel(
    val namespace: String,
    private val listMemories: ListMemoriesUseCase,
    private val listConflicts: ListConflictsUseCase,
    private val createMemory: CreateMemoryUseCase,
    private val navigator: EdgeNavigator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AssetDetailUiState())
    val uiState: StateFlow<AssetDetailUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(data = LoadableState.Loading) }
            try {
                val memories = listMemories()
                val records = AssetModel.recordsFor(memories, namespace)
                val unresolvedConflicts = listConflicts().filter {
                    AssetModel.namespaceOf(it.subjectKey) == namespace &&
                        it.state == com.example.EdgeMemo.domain.conflict.ConflictResolutionState.UNRESOLVED
                }
                if (records.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            data = LoadableState.Ready(
                                MachineDetailData(
                                    asset = Asset(
                                        namespace = namespace,
                                        recordCount = 0,
                                        lastActivityAt = 0L,
                                        representativeTitle = namespace,
                                        maintenanceCount = 0,
                                        pendingSyncCount = 0,
                                        unresolvedConflictCount = 0L,
                                        types = emptyList(),
                                    ),
                                    records = emptyList(),
                                    conflictCount = unresolvedConflicts.size.toLong(),
                                    conflicts = unresolvedConflicts,
                                ),
                            ),
                        )
                    }
                    return@launch
                }
                val assets = AssetModel.deriveAssets(memories)
                val asset = assets.first { it.namespace == namespace }
                _uiState.update {
                    it.copy(
                        data = LoadableState.Ready(
                            MachineDetailData(
                                asset = asset.copy(
                                    unresolvedConflictCount = unresolvedConflicts.size.toLong(),
                                ),
                                records = records,
                                conflictCount = unresolvedConflicts.size.toLong(),
                                conflicts = unresolvedConflicts,
                            ),
                        ),
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        data = LoadableState.Failed(
                            e.message ?: "asset records could not be read",
                        ),
                    )
                }
            }
        }
    }

    fun back(): Boolean = navigator.pop()

    fun setCategoryFilter(category: AssetModel.AssetRecordCategory?) {
        _uiState.update { it.copy(categoryFilter = category) }
    }

    fun openRecord(memoryId: String) = navigator.openRecord(memoryId)

    /** UI Phase 4 — a conflict row opens the real evidence/resolution workflow. */
    fun openConflict(conflictId: String) = navigator.openConflict(conflictId)

    /**
     * Opens the SAME Ask experience grounded on this asset (UI Phase 2): the
     * navigator carries the asset namespace into the shared Ask screen, which
     * appends it to the executed query for the real retrieval pipeline. No
     * second Ask surface, no UI-side result filter.
     */
    fun askAboutAsset() = navigator.openAsk(namespace)

    // ── Add-observation flow (real CreateMemoryUseCase, asset-associated) ──

    /** Existing entry point now opens the focused observation workflow. */
    fun openComposer() = openComposerFor(MemoryType.OBSERVATION)

    fun openObservationComposer() = openComposerFor(MemoryType.OBSERVATION)

    /** REPAIR is already supported by CreateMemoryUseCase; no new write path. */
    fun openMaintenanceComposer() = openComposerFor(MemoryType.REPAIR)

    private fun openComposerFor(type: MemoryType) {
        _uiState.update {
            it.copy(
                composer = AssetComposerState(
                    open = true,
                    type = type,
                ),
            )
        }
    }

    fun closeComposer() {
        _uiState.update {
            it.copy(composer = AssetComposerState(open = false))
        }
    }

    fun onComposerTitleChange(value: String) {
        _uiState.update { it.copy(composer = it.composer.copy(title = value, error = null)) }
    }

    fun onComposerContentChange(value: String) {
        _uiState.update { it.copy(composer = it.composer.copy(content = value, error = null)) }
    }

    fun onComposerTypeChange(value: MemoryType) {
        _uiState.update { it.copy(composer = it.composer.copy(type = value, error = null)) }
    }

    fun onComposerSyncChoice(value: com.example.EdgeMemo.core.model.SyncDecision?) {
        _uiState.update { it.copy(composer = it.composer.copy(syncChoice = value, error = null)) }
    }

    /**
     * Creates an asset-associated record through the PRODUCTION
     * `CreateMemoryUseCase` (which persists to the Qdrant shard and runs the
     * frozen change detection). The subject key embeds the asset namespace so
     * the new record joins this asset's derived view, and the list refreshes
     * through the normal domain read afterwards.
     */
    fun submitComposer() {
        val composer = _uiState.value.composer
        if (composer.submitting) return
        if (composer.title.isBlank() && composer.content.isBlank()) {
            _uiState.update {
                it.copy(composer = composer.copy(error = "A title or description is required."))
            }
            return
        }
        _uiState.update { it.copy(composer = composer.copy(submitting = true, error = null)) }
        viewModelScope.launch {
            try {
                val created = createMemory(
                    CreateMemoryInput(
                        title = composer.title.trim(),
                        content = composer.content.trim(),
                        type = composer.type,
                        subjectKey = "$namespace/${composer.type.name.lowercase()}",
                        userSyncChoice = composer.syncChoice,
                    ),
                )
                _uiState.update {
                    it.copy(
                        composer = AssetComposerState(
                            type = created.type,
                            createdMessage = "Saved to local memory for ${namespace.uppercase()}.",
                            createdSyncState = created.syncState,
                        ),
                    )
                }
                refresh()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        composer = it.composer.copy(
                            submitting = false,
                            error = e.message ?: "the record could not be stored",
                        ),
                    )
                }
            }
        }
    }

    fun dismissCreatedMessage() {
        _uiState.update { it.copy(composer = it.composer.copy(createdMessage = null)) }
    }
}

/**
 * Single-record detail for the asset workspace, resolved by id through the
 * existing Qdrant-native domain use case. This avoids re-listing the full
 * application knowledge set for one row.
 */
class RecordDetailViewModel(
    private val memoryId: String,
    private val getMemory: GetMemoryUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoadableState<Memory?>>(LoadableState.Loading)
    val uiState: StateFlow<LoadableState<Memory?>> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = LoadableState.Loading
            try {
                _uiState.value = LoadableState.Ready(getMemory(memoryId))
            } catch (e: Exception) {
                _uiState.value = LoadableState.Failed(
                    e.message ?: "record could not be read",
                )
            }
        }
    }
}
