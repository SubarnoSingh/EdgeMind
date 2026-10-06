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
    private val createMemory: CreateMemoryUseCase,
    val navigator: EdgeNavigator,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoadableState<List<Asset>>>(LoadableState.Loading)
    val uiState: StateFlow<LoadableState<List<Asset>>> = _uiState.asStateFlow()

    private val _creation = MutableStateFlow(AddMachineState())
    val creation: StateFlow<AddMachineState> = _creation.asStateFlow()

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

    // ── Add Machine (real asset creation through the production record path) ──

    fun onMachineIdChange(value: String) {
        _creation.update { it.copy(id = value, error = null) }
    }

    fun onMachineNameChange(value: String) {
        _creation.update { it.copy(name = value, error = null) }
    }

    fun openAddMachine() {
        _creation.update { AddMachineState(open = true) }
    }

    fun closeAddMachine() {
        _creation.update { AddMachineState() }
    }

    /**
     * Creates a machine/asset by persisting a real definition RECORD through the
     * production [CreateMemoryUseCase] (Qdrant-backed). The record's subject key
     * is `<canonical-token>/machine`, so [AssetModel.namespaceOf] derives the
     * asset namespace from the machine id — the same mechanism the whole app
     * already uses. Arbitrary ids are supported (P-102, P-201, …); nothing is
     * hardcoded and no separate machine store is introduced. The definition
     * record is excluded from timelines/counts, so the new machine opens with an
     * EMPTY activity view, and records later captured under its namespace stay
     * isolated from other machines. On success the caller is offered "Add
     * initial record" or "Skip for now".
     */
    fun addMachine() {
        val current = _creation.value
        if (current.submitting) return
        val idToken = machineIdToken(current.id)
        if (idToken == null) {
            _creation.update { it.copy(error = "Enter a machine ID, like P-102.") }
            return
        }
        // Reject duplicates / collisions BEFORE creating — never merge, never
        // delete existing records. The canonical rule makes P102 and P-102 the
        // same machine, and also catches a new id that maps onto an existing
        // (possibly hyphenated) namespace such as the p-101 demo.
        findExistingNamespace(idToken)?.let { existing ->
            val shown = existing.uppercase()
            _creation.update {
                it.copy(error = "$shown already exists. Open it from the list to add records.")
            }
            return
        }
        _creation.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val label = current.name.trim()
                createMemory(
                    CreateMemoryInput(
                        title = label.ifEmpty { idToken.uppercase() },
                        content = label.ifEmpty { "Machine/asset $idToken." },
                        type = MemoryType.NOTE,
                        tags = listOf(AssetModel.MACHINE_METADATA_VALUE),
                        subjectKey = AssetModel.machineDefinitionSubjectKey(idToken),
                        metadata = mapOf(
                            AssetModel.MACHINE_METADATA_KEY to AssetModel.MACHINE_METADATA_VALUE,
                        ),
                        // An asset definition is on-device operational metadata:
                        // keep it local so it never enters the sync outbox.
                        userSyncChoice = com.example.EdgeMemo.core.model.SyncDecision.LOCAL_ONLY,
                    ),
                )
                _creation.update {
                    AddMachineState(createdId = idToken, createdName = label.ifEmpty { null })
                }
                refresh()
            } catch (e: Exception) {
                _creation.update {
                    it.copy(
                        submitting = false,
                        error = e.message ?: "the machine could not be created",
                    )
                }
            }
        }
    }

    /**
     * Offers the just-created machine for immediate capture: opens the EXISTING
     * record composer (which supports Observation / Repair / Event / Procedure /
     * Document + the ingestion pipeline), locked to this machine's namespace so
     * every initial record stays isolated.
     */
    fun addInitialRecord() {
        val token = _creation.value.createdId ?: return
        val displayName = _creation.value.createdName ?: token.uppercase()
        navigator.openCreateRecordForMachine(subject = token, displayName = displayName)
    }

    /** "Skip for now" — open the new machine's (empty) detail screen. */
    fun skipInitialRecords() {
        val token = _creation.value.createdId ?: return
        closeAddMachine()
        openAsset(token)
    }

    private fun findExistingNamespace(idToken: String): String? =
        (uiState.value as? LoadableState.Ready)?.value
            ?.map { it.namespace }
            ?.firstOrNull { AssetModel.canonicalAssetToken(it) == idToken }

    companion object {
        /**
         * Normalizes free-typed machine id text into the single canonical
         * subject-key token (the asset namespace) used for both creation and
         * duplicate detection. See [AssetModel.canonicalAssetToken].
         */
        fun machineIdToken(raw: String): String? = AssetModel.canonicalAssetToken(raw)
    }
}

/** Transient state for the Add Machine form on the Machines screen. */
data class AddMachineState(
    val open: Boolean = false,
    val id: String = "",
    val name: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
    /** Canonical namespace token of the machine just created (drives the choice). */
    val createdId: String? = null,
    /** Optional human name for the just-created machine. */
    val createdName: String? = null,
)

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
    /**
     * True while the composer is shown as a reusable, always-available form:
     * after a successful save the fields clear but the form stays open so more
     * evidence can be added to the same machine without re-entering the screen.
     */
    val keepOpenAfterSave: Boolean = false,
) {
    /**
     * The asset record types a technician can capture on this machine. Kept in
     * the presentation layer only; each maps to an existing [MemoryType] that
     * the production `CreateMemoryUseCase` already persists.
     */
    val assetRecordTypes: List<MemoryType> = listOf(
        MemoryType.OBSERVATION,
        MemoryType.REPAIR,
        MemoryType.EVENT,
        MemoryType.PROCEDURE,
    )
}

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
                val assets = AssetModel.deriveAssets(memories)
                if (records.isEmpty()) {
                    // A machine that exists only as a definition (freshly added,
                    // no activity yet) still resolves through deriveAssets so its
                    // real name is preserved; otherwise fall back to the namespace.
                    val definedAsset = assets.firstOrNull { it.namespace == namespace }
                    _uiState.update {
                        it.copy(
                            data = LoadableState.Ready(
                                MachineDetailData(
                                    asset = (definedAsset ?: Asset(
                                        namespace = namespace,
                                        recordCount = 0,
                                        lastActivityAt = 0L,
                                        representativeTitle = namespace,
                                        maintenanceCount = 0,
                                        pendingSyncCount = 0,
                                        unresolvedConflictCount = 0L,
                                        types = emptyList(),
                                    )).copy(unresolvedConflictCount = unresolvedConflicts.size.toLong()),
                                    records = emptyList(),
                                    conflictCount = unresolvedConflicts.size.toLong(),
                                    conflicts = unresolvedConflicts,
                                ),
                            ),
                        )
                    }
                    return@launch
                }
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
                    // Reusable form: stays available after each save so more
                    // evidence can be captured on the same machine.
                    keepOpenAfterSave = true,
                ),
            )
        }
    }

    /** Event and procedure capture on the same machine (existing MemoryTypes). */
    fun openEventComposer() = openComposerFor(MemoryType.EVENT)

    fun openProcedureComposer() = openComposerFor(MemoryType.PROCEDURE)

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
     *
     * On success the form CLEARS but stays open (one-shot confirmation shown),
     * so a technician can add a second, third, … record to the same machine
     * without leaving the screen. The record always goes through the real
     * persistence path — never a local-only UI simulation.
     */
    fun submitComposer() {
        val composer = _uiState.value.composer
        if (composer.submitting) return
        if (composer.title.isBlank() && composer.content.isBlank()) {
            _uiState.update {
                it.copy(composer = composer.copy(error = "Add a title or a few words about what happened."))
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
                        composer = it.composer.copy(
                            open = it.composer.keepOpenAfterSave,
                            title = "",
                            content = "",
                            type = created.type,
                            submitting = false,
                            error = null,
                            createdMessage = "Saved to ${namespace.uppercase()}.",
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
