package com.example.EdgeMemo.presentation.machines

import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemorySensitivity
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.RetrievedMemory
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.domain.conflict.ConflictRepository
import com.example.EdgeMemo.domain.conflict.ConflictResolutionState
import com.example.EdgeMemo.domain.conflict.ListConflictsUseCase
import com.example.EdgeMemo.domain.memory.CreateMemoryUseCase
import com.example.EdgeMemo.domain.memory.ListMemoriesUseCase
import com.example.EdgeMemo.domain.memory.MemoryRepository
import com.example.EdgeMemo.domain.memory.MemoryWritePhase
import com.example.EdgeMemo.presentation.machines.AssetModel.AssetRecordCategory
import com.example.EdgeMemo.presentation.record.CreateRecordViewModel
import com.example.EdgeMemo.presentation.shell.EdgeNavigator
import com.example.EdgeMemo.presentation.shell.EdgeRoute
import com.example.EdgeMemo.presentation.shell.EdgeTab
import com.example.EdgeMemo.presentation.shell.LoadableState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UI Phase 5 contract tests for the operational activity state. Persistence is
 * represented only by the existing MemoryRepository boundary here; the real
 * Qdrant production path is covered by [OperationalActivityEndToEndTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OperationalActivityPhase5Test {

    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeMemoryRepository(
        val records: MutableList<Memory> = mutableListOf(),
    ) : MemoryRepository {
        var failRead = false
        var failWrite = false
        var createGate: CompletableDeferred<Unit>? = null
        var createCalls = 0
        var lastInput: CreateMemoryInput? = null
        val subjectKeys = mutableListOf<String?>()

        override suspend fun create(input: CreateMemoryInput): Memory {
            createCalls += 1
            createGate?.await()
            if (failWrite) error("local write failed")
            lastInput = input
            subjectKeys += input.subjectKey
            val state = if (input.userSyncChoice == SyncDecision.SYNC) {
                MemorySyncState.PENDING
            } else {
                MemorySyncState.LOCAL
            }
            return memory(
                id = "created-$createCalls",
                title = input.title,
                content = input.content,
                type = input.type,
                subjectKey = input.subjectKey,
                updatedAt = 10_000L + createCalls,
                syncState = state,
                syncDecision = input.userSyncChoice ?: SyncDecision.LOCAL_ONLY,
            ).copy(tags = input.tags, metadata = input.metadata).also(records::add)
        }

        override suspend fun createAll(
            inputs: List<CreateMemoryInput>,
            onPhase: (MemoryWritePhase) -> Unit,
        ): List<Memory> = inputs.map { create(it) }

        override suspend fun update(memory: Memory): Memory = memory
        override suspend fun delete(memoryId: String) {
            records.removeAll { it.memoryId == memoryId }
        }

        override suspend fun get(memoryId: String): Memory? = records.firstOrNull { it.memoryId == memoryId }

        override suspend fun list(): List<Memory> {
            if (failRead) error("local read failed")
            return records.toList()
        }

        override suspend fun search(query: String, limit: Int): List<RetrievedMemory> = emptyList()
        override suspend fun count(): Long = records.size.toLong()
    }

    private class FakeConflictRepository(
        val conflicts: MutableList<Conflict> = mutableListOf(),
    ) : ConflictRepository {
        override suspend fun list(): List<Conflict> = conflicts.toList()
        override suspend fun get(conflictId: String): Conflict? =
            conflicts.firstOrNull { it.conflictId == conflictId }

        override suspend fun countUnresolved(): Long =
            conflicts.count { it.state == ConflictResolutionState.UNRESOLVED }.toLong()
    }

    private fun viewModel(
        repository: FakeMemoryRepository,
        conflicts: FakeConflictRepository = FakeConflictRepository(),
        navigator: EdgeNavigator = EdgeNavigator(),
    ): MachineDetailViewModel = MachineDetailViewModel(
        namespace = "p101",
        listMemories = ListMemoriesUseCase(repository),
        listConflicts = ListConflictsUseCase(conflicts),
        createMemory = CreateMemoryUseCase(repository),
        navigator = navigator,
    )

    @Test
    fun loadingEmptyErrorAndRetryRemainDistinct() = runTest(dispatcher) {
        val repository = FakeMemoryRepository()
        val viewModel = viewModel(repository)
        assertTrue(viewModel.uiState.value.data is LoadableState.Loading)

        advanceUntilIdle()
        val empty = viewModel.uiState.value.data as LoadableState.Ready
        assertTrue(empty.value.records.isEmpty())

        repository.failRead = true
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals("local read failed", (viewModel.uiState.value.data as LoadableState.Failed).message)

        repository.failRead = false
        repository.records += memory("real", subjectKey = "p101/seal")
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, (viewModel.uiState.value.data as LoadableState.Ready).value.records.size)
    }

    @Test
    fun summaryAndFocusedSectionsUseTheExistingMemoryTaxonomyOnly() = runTest(dispatcher) {
        val records = mutableListOf(
            memory("repair", "Seal replacement", "Faces replaced", MemoryType.REPAIR, "p101/seal", 700),
            memory("obs", "Seal noise", "Noise observed", MemoryType.OBSERVATION, "p101/seal", 600),
            memory("event", "Trip", "Motor protection trip", MemoryType.EVENT, "p101/event", 500),
            memory("procedure", "Restart", "Verify flush", MemoryType.PROCEDURE, "p101/procedure", 400),
            memory("document", "Manual", "OEM manual section", MemoryType.DOCUMENT, "p101/manual", 300),
            memory("note", "Shift note", "Handover", MemoryType.NOTE, "p101/note", 200),
            memory("cloud", "Advisory", "Cloud knowledge", MemoryType.CLOUD_KNOWLEDGE, "p101/advice", 100),
        )
        val viewModel = viewModel(FakeMemoryRepository(records))
        advanceUntilIdle()
        val data = (viewModel.uiState.value.data as LoadableState.Ready).value

        assertEquals(7, data.records.size)
        assertEquals(listOf("repair"), data.maintenanceRecords.map { it.memoryId })
        assertEquals(listOf("obs"), data.observationRecords.map { it.memoryId })
        assertEquals(listOf("event"), data.incidentRecords.map { it.memoryId })
        assertEquals(listOf("procedure"), data.procedureRecords.map { it.memoryId })
        assertEquals(listOf("document"), data.documentRecords.map { it.memoryId })
        assertEquals(2, data.countsByCategory[AssetRecordCategory.OTHER])
        assertEquals("Faces replaced", data.maintenanceRecords.single().content)
    }

    @Test
    fun activityOrderIsNewestFirstThenMemoryIdAscendingAndMissingTimeIsNotInvented() = runTest(dispatcher) {
        val records = mutableListOf(
            memory("z-id", updatedAt = 900, subjectKey = "p101/x"),
            memory("a-id", updatedAt = 900, subjectKey = "p101/x"),
            memory("older", updatedAt = 800, subjectKey = "p101/x"),
            memory("missing", updatedAt = 0, subjectKey = "p101/x"),
        )
        val viewModel = viewModel(FakeMemoryRepository(records))
        advanceUntilIdle()
        val data = (viewModel.uiState.value.data as LoadableState.Ready).value

        assertEquals(listOf("a-id", "z-id", "older", "missing"), data.records.map { it.memoryId })
        assertEquals("—", formatRecordTimestamp(data.records.last().updatedAt))
    }

    @Test
    fun filteringIsPresentationOnlyAcrossEveryRealCategory() = runTest(dispatcher) {
        val records = MemoryType.entries.mapIndexed { index, type ->
            memory(
                id = type.name.lowercase(),
                type = type,
                subjectKey = "p101/${type.name.lowercase()}",
                updatedAt = 1_000L - index,
            )
        }.toMutableList()
        val repository = FakeMemoryRepository(records)
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        AssetRecordCategory.entries.forEach { category ->
            viewModel.setCategoryFilter(category)
            assertTrue(viewModel.uiState.value.visibleRecords.isNotEmpty())
            assertTrue(viewModel.uiState.value.visibleRecords.all { AssetModel.categoryOf(it.type) == category })
        }
        assertEquals(0, repository.createCalls) // filtering performs no write
        viewModel.setCategoryFilter(null)
        assertEquals(records.size, viewModel.uiState.value.visibleRecords.size)
    }

    @Test
    fun maintenanceRowsContainOnlyRepairAndNavigateToExistingRecordDetail() = runTest(dispatcher) {
        val navigator = EdgeNavigator().also {
            it.select(EdgeTab.MACHINES)
            it.openMachine("p101")
        }
        val repository = FakeMemoryRepository(
            mutableListOf(
                memory("repair", "Replaced seal", "Installed new faces", MemoryType.REPAIR, "p101/seal", 20),
                memory("observation", "Observed heat", "Warm gland", MemoryType.OBSERVATION, "p101/seal", 10),
            ),
        )
        val viewModel = viewModel(repository, navigator = navigator)
        advanceUntilIdle()
        val data = (viewModel.uiState.value.data as LoadableState.Ready).value

        assertEquals(listOf("repair"), data.maintenanceRecords.map { it.memoryId })
        assertEquals("Replaced seal", data.maintenanceRecords.single().title)
        viewModel.openRecord(data.maintenanceRecords.single().memoryId)
        assertEquals(EdgeRoute.RecordDetail("repair"), navigator.current)
    }

    @Test
    fun observationValidationPersistenceRefreshAndTruthfulSyncState() = runTest(dispatcher) {
        val repository = FakeMemoryRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.openObservationComposer()
        assertEquals(MemoryType.OBSERVATION, viewModel.uiState.value.composer.type)
        viewModel.submitComposer()
        assertEquals("A title or description is required.", viewModel.uiState.value.composer.error)
        assertEquals(0, repository.createCalls)

        viewModel.onComposerTitleChange("Seal vibration observed")
        viewModel.onComposerContentChange("Vibration increased during visual inspection.")
        viewModel.onComposerSyncChoice(SyncDecision.SYNC)
        viewModel.submitComposer()
        assertTrue(viewModel.uiState.value.composer.submitting)
        advanceUntilIdle()

        assertEquals(MemoryType.OBSERVATION, repository.lastInput?.type)
        assertEquals("p101/observation", repository.lastInput?.subjectKey)
        assertEquals(SyncDecision.SYNC, repository.lastInput?.userSyncChoice)
        assertEquals(MemorySyncState.PENDING, viewModel.uiState.value.composer.createdSyncState)
        val data = (viewModel.uiState.value.data as LoadableState.Ready).value
        assertEquals(listOf("created-1"), data.observationRecords.map { it.memoryId })
        assertEquals(MemorySyncState.PENDING, data.observationRecords.single().syncState)
    }

    @Test
    fun submittingStatePreventsDoubleSubmission() = runTest(dispatcher) {
        val repository = FakeMemoryRepository().apply {
            createGate = CompletableDeferred()
        }
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.openObservationComposer()
        viewModel.onComposerContentChange("One deliberate field observation")

        viewModel.submitComposer()
        viewModel.submitComposer()
        assertTrue(viewModel.uiState.value.composer.submitting)
        runCurrent()
        assertEquals(1, repository.createCalls)

        repository.createGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, repository.records.size)
        assertFalse(viewModel.uiState.value.composer.submitting)
    }

    @Test
    fun logMaintenanceUsesTheSameProductionBoundaryWithRepairType() = runTest(dispatcher) {
        val repository = FakeMemoryRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.openMaintenanceComposer()
        assertEquals(MemoryType.REPAIR, viewModel.uiState.value.composer.type)
        viewModel.onComposerTitleChange("Seal replaced")
        viewModel.onComposerContentChange("Removed damaged faces and installed approved seal.")
        viewModel.submitComposer()
        advanceUntilIdle()

        assertEquals(MemoryType.REPAIR, repository.lastInput?.type)
        assertEquals("p101/repair", repository.lastInput?.subjectKey)
        val data = (viewModel.uiState.value.data as LoadableState.Ready).value
        assertEquals(listOf("created-1"), data.maintenanceRecords.map { it.memoryId })
    }

    @Test
    fun addComposerStaysAvailableAndSupportsRepeatedAddsOnTheSameMachine() = runTest(dispatcher) {
        val repository = FakeMemoryRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        // First observation saved on an asset with no prior records.
        viewModel.openObservationComposer()
        viewModel.onComposerTitleChange("First observation")
        viewModel.onComposerContentChange("Seal weep at 1200h.")
        viewModel.submitComposer()
        advanceUntilIdle()

        // The reusable form must STAY open (add action never disappears) with a
        // cleared title/content plus an honest confirmation, not a collapsed
        // one-shot state that hides the add UI.
        val afterFirst = viewModel.uiState.value.composer
        assertTrue("composer stays open after save", afterFirst.open)
        assertTrue("form cleared for the next record", afterFirst.title.isEmpty() && afterFirst.content.isEmpty())
        assertEquals("Saved to local memory for P101.", afterFirst.createdMessage)

        // Second record: a different type on the SAME machine, without leaving.
        viewModel.onComposerTypeChange(MemoryType.EVENT)
        viewModel.onComposerTitleChange("Dry-run event")
        viewModel.onComposerContentChange("Short dry run during test.")
        viewModel.submitComposer()
        advanceUntilIdle()

        // Third record: switch type again and add a procedure.
        viewModel.onComposerTypeChange(MemoryType.PROCEDURE)
        viewModel.onComposerTitleChange("Restart procedure")
        viewModel.onComposerContentChange("Verify submergence before restart.")
        viewModel.submitComposer()
        advanceUntilIdle()

        // Every add went through the production create path (three writes).
        assertEquals(3, repository.createCalls)
        assertEquals(listOf("p101/observation", "p101/event", "p101/procedure"), repository.subjectKeys)

        // All three real records are present under the machine's categories.
        val data = (viewModel.uiState.value.data as LoadableState.Ready).value
        assertEquals(1, data.observationRecords.size)
        assertEquals(1, data.incidentRecords.size)
        assertEquals(1, data.procedureRecords.size)
        assertEquals(3, data.records.size)
    }

    @Test
    fun logEventAndProcedureUseTheSameProductionBoundary() = runTest(dispatcher) {
        val repository = FakeMemoryRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.openEventComposer()
        assertEquals(MemoryType.EVENT, viewModel.uiState.value.composer.type)
        viewModel.onComposerContentChange("Bearings ran hot during shift.")
        viewModel.submitComposer()
        advanceUntilIdle()
        assertEquals("p101/event", repository.lastInput?.subjectKey)

        viewModel.openProcedureComposer()
        assertEquals(MemoryType.PROCEDURE, viewModel.uiState.value.composer.type)
        viewModel.onComposerContentChange("Isolate, then drain before opening the casing.")
        viewModel.submitComposer()
        advanceUntilIdle()
        assertEquals("p101/procedure", repository.lastInput?.subjectKey)

        assertEquals(2, repository.createCalls)
    }

    @Test
    fun cancelComposerClosesTheReusableForm() = runTest(dispatcher) {
        val repository = FakeMemoryRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.openObservationComposer()
        viewModel.onComposerTitleChange("Draft")
        viewModel.closeComposer()
        assertFalse(viewModel.uiState.value.composer.open)
        assertEquals(0, repository.createCalls)
    }

    @Test
    fun realRecordConflictIsJoinedOnceAndRoutesToPhase4Detail() = runTest(dispatcher) {
        val navigator = EdgeNavigator().also {
            it.select(EdgeTab.MACHINES)
            it.openMachine("p101")
        }
        val repository = FakeMemoryRepository(mutableListOf(memory("local", subjectKey = "p101/seal")))
        val conflicts = FakeConflictRepository(
            mutableListOf(
                conflict("conflict-b", localMemoryId = "local", detectedAt = 200),
                conflict("conflict-a", localMemoryId = "local", detectedAt = 200),
            ),
        )
        val viewModel = viewModel(repository, conflicts, navigator)
        advanceUntilIdle()
        val data = (viewModel.uiState.value.data as LoadableState.Ready).value

        assertEquals(listOf("conflict-a", "conflict-b"), data.conflictsFor("local").map { it.conflictId })
        viewModel.openConflict(data.conflictsFor("local").first().conflictId)
        assertEquals(EdgeRoute.ConflictDetail("conflict-a"), navigator.current)
    }

    @Test
    fun askAboutAssetUsesTheOneExistingAskRouteAndNamespace() = runTest(dispatcher) {
        val navigator = EdgeNavigator().also {
            it.select(EdgeTab.MACHINES)
            it.openMachine("p101")
        }
        val viewModel = viewModel(FakeMemoryRepository(), navigator = navigator)
        advanceUntilIdle()

        viewModel.askAboutAsset()
        assertEquals(EdgeRoute.Ask, navigator.current)
        assertEquals("p101", navigator.askAsset.value)
    }

    @Test
    fun pendingFailedAndSyncedStatesRemainUnmodified() = runTest(dispatcher) {
        val repository = FakeMemoryRepository(
            mutableListOf(
                memory("pending", subjectKey = "p101/x", syncState = MemorySyncState.PENDING),
                memory("failed", subjectKey = "p101/x", syncState = MemorySyncState.FAILED),
                memory("synced", subjectKey = "p101/x", syncState = MemorySyncState.SYNCED),
                memory("local", subjectKey = "p101/x", syncState = MemorySyncState.LOCAL),
            ),
        )
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        val data = (viewModel.uiState.value.data as LoadableState.Ready).value

        assertEquals(1, data.pendingSyncRecords)
        assertEquals(1, data.failedSyncRecords)
        assertEquals(
            setOf(MemorySyncState.PENDING, MemorySyncState.FAILED, MemorySyncState.SYNCED, MemorySyncState.LOCAL),
            data.records.map { it.syncState }.toSet(),
        )
    }

    @Test
    fun presentationHasNoForbiddenPersistenceNativeNetworkOrWorkerImports() {
        val projectRoot = sequenceOf(File(System.getProperty("user.dir")), File(System.getProperty("user.dir")).parentFile)
            .first { File(it, "app/src/main/java").isDirectory }
        val presentation = File(projectRoot, "app/src/main/java/com/example/EdgeMemo/presentation")
        val sources = presentation.walkTopDown().filter { it.extension == "kt" }.toList()
        val forbidden = listOf(
            "import androidx.room.",
            "import androidx.work.",
            "import com.example.EdgeMemo.native.",
            "import com.example.EdgeMemo.data.local.",
            "import com.example.EdgeMemo.data.remote.",
            "import com.example.EdgeMemo.data.sync.",
            "QdrantConflictStore",
            "QdrantConflictResolver",
            "SyncOperationStore",
            "CloudHttpClient",
            "HttpURLConnection",
            "local_qdrant",
        )
        sources.forEach { source ->
            val text = source.readText()
            forbidden.forEach { token ->
                assertFalse("${source.relativeTo(projectRoot)} must not reference $token", text.contains(token))
            }
        }

        val mainSources = File(projectRoot, "app/src/main/java")
            .walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertEquals(1, Regex("\\\"qdrant_sync_store\\\"").findAll(mainSources).count())
        assertEquals(0, Regex("\\\"local_qdrant\\\"").findAll(mainSources).count())
    }

    // ── Add Machine (real asset creation through the production record path) ──

    private fun machinesViewModel(
        repository: FakeMemoryRepository,
        conflicts: FakeConflictRepository = FakeConflictRepository(),
        navigator: EdgeNavigator = EdgeNavigator(),
    ): MachinesViewModel = MachinesViewModel(
        listMemories = ListMemoriesUseCase(repository),
        listConflicts = ListConflictsUseCase(conflicts),
        createMemory = CreateMemoryUseCase(repository),
        navigator = navigator,
    )

    @Test
    fun machineIdTokenAppliesOneCanonicalRuleAcrossHyphensAndCase() {
        // P102 and P-102 (and p 102 / P.102) MUST collapse to one token so the
        // UI can no longer show them as two machines.
        assertEquals("p102", MachinesViewModel.machineIdToken("  P-102  "))
        assertEquals("p102", MachinesViewModel.machineIdToken("P102"))
        assertEquals("p102", MachinesViewModel.machineIdToken("P 102"))
        assertEquals("p102", MachinesViewModel.machineIdToken("p/102"))
        assertEquals("p102", MachinesViewModel.machineIdToken("P.102"))
        assertEquals("lineb", MachinesViewModel.machineIdToken("Line/B"))
        assertNull(MachinesViewModel.machineIdToken("   "))
        assertNull(MachinesViewModel.machineIdToken("///"))
        assertNull(MachinesViewModel.machineIdToken("###"))
        // AssetModel exposes the same single rule.
        assertEquals("p102", AssetModel.canonicalAssetToken("P-102"))
        assertEquals("p102", AssetModel.canonicalAssetToken("P102"))
    }

    @Test
    fun addMachinePersistsRealDefinitionRecordUnderMachineNamespace() = runTest(dispatcher) {
        val repository = FakeMemoryRepository()
        val vm = machinesViewModel(repository)
        advanceUntilIdle()

        vm.openAddMachine()
        vm.onMachineIdChange("P-102")
        vm.onMachineNameChange("Coolant pump")
        vm.addMachine()
        advanceUntilIdle()

        // Exactly one REAL record was written through the production path.
        assertEquals(1, repository.records.size)
        val definition = repository.records.single()
        assertEquals("p102/machine", definition.subjectKey)
        assertEquals("machine", definition.metadata["assetKind"])
        assertEquals(MemoryType.NOTE, definition.type)
        assertEquals("Coolant pump", definition.title)
        // A machine definition is operational-local: it must never enter outbox.
        assertEquals(SyncDecision.LOCAL_ONLY, definition.syncDecision)
        assertEquals(MemorySyncState.LOCAL, definition.syncState)

        // Success collapses the form into the initial-record choice and the
        // list now contains the asset with NO activity yet.
        val creation = vm.creation.value
        assertEquals("p102", creation.createdId)
        assertEquals("Coolant pump", creation.createdName)
        assertFalse(creation.open)
        val asset = (vm.uiState.value as LoadableState.Ready).value.single()
        assertEquals("p102", asset.namespace)
        assertEquals("Coolant pump", asset.representativeTitle)
        assertEquals(0, asset.recordCount)
    }

    @Test
    fun createdMachineActivityRecordsStayIsolatedFromOtherMachines() = runTest(dispatcher) {
        val repository = FakeMemoryRepository()
        repository.records += memory("p101-a", subjectKey = "p-101/torque", type = MemoryType.REPAIR)
        val vm = machinesViewModel(repository)
        advanceUntilIdle()

        vm.openAddMachine()
        vm.onMachineIdChange("P-102")
        vm.addMachine()
        advanceUntilIdle()

        // Later capture against the new machine namespace (bypassing the UI).
        repository.records += memory(
            "p102-a", title = "Overheating", subjectKey = "p102/observation",
            type = MemoryType.OBSERVATION,
        )
        vm.refresh()
        advanceUntilIdle()

        val assets = (vm.uiState.value as LoadableState.Ready).value.associateBy { it.namespace }
        assertEquals(listOf("p-101", "p102"), assets.keys.sorted())
        assertEquals(1, assets.getValue("p-101").recordCount)
        // The machine-definition record is NOT counted as p102 activity.
        assertEquals(1, assets.getValue("p102").recordCount)

        // Activity timelines never cross namespaces.
        assertEquals(
            listOf("p102-a"),
            AssetModel.recordsFor(repository.records, "p102").map { it.memoryId },
        )
        assertEquals(
            listOf("p101-a"),
            AssetModel.recordsFor(repository.records, "p-101").map { it.memoryId },
        )
    }

    @Test
    fun addMachineRejectsBlankAndCanonicalDuplicateIds() = runTest(dispatcher) {
        val repository = FakeMemoryRepository()
        val vm = machinesViewModel(repository)
        advanceUntilIdle()

        // Blank id: nothing persisted, inline error surfaced.
        vm.openAddMachine()
        vm.onMachineIdChange("   ")
        vm.addMachine()
        advanceUntilIdle()
        assertTrue(repository.records.isEmpty())
        assertNotNull(vm.creation.value.error)

        // First valid create.
        vm.openAddMachine()
        vm.onMachineIdChange("P-102")
        vm.addMachine()
        advanceUntilIdle()
        assertEquals(1, repository.records.size)

        // "P102" is the SAME machine under the canonical rule → rejected, never
        // double-written (this is the reported P102 vs P-102 duplicate bug).
        vm.openAddMachine()
        vm.onMachineIdChange("P102")
        vm.addMachine()
        advanceUntilIdle()
        assertNotNull(vm.creation.value.error)
        assertEquals(1, repository.records.size)
    }

    @Test
    fun addMachineReportsCollisionWithExistingHyphenatedMachineWithoutMerging() = runTest(dispatcher) {
        // Existing demo-style hyphenated namespace p-101 (untouched).
        val repository = FakeMemoryRepository()
        repository.records += memory("seed", subjectKey = "p-101/torque", type = MemoryType.REPAIR)
        val vm = machinesViewModel(repository)
        advanceUntilIdle()
        val before = repository.records.size

        // Creating "P-101" canonicalizes to p101 which maps onto p-101. It must
        // be refused safely — no merge, no new record, existing data preserved.
        vm.openAddMachine()
        vm.onMachineIdChange("P-101")
        vm.addMachine()
        advanceUntilIdle()

        assertNotNull(vm.creation.value.error)
        assertEquals(before, repository.records.size) // nothing written
        assertTrue(vm.uiState.value.let { (it as LoadableState.Ready).value.map { a -> a.namespace } } == listOf("p-101"))
    }

    @Test
    fun addInitialRecordOpensMachineLockedCapture() = runTest(dispatcher) {
        val navigator = EdgeNavigator()
        val repository = FakeMemoryRepository()
        val vm = machinesViewModel(repository, navigator = navigator)
        advanceUntilIdle()

        vm.openAddMachine()
        vm.onMachineIdChange("P-102")
        vm.onMachineNameChange("Coolant pump")
        vm.addMachine()
        advanceUntilIdle()

        vm.addInitialRecord()
        assertEquals(EdgeRoute.CreateRecord, navigator.current)
        val capture = navigator.machineCapture.value
        assertEquals("p102", capture?.subject)
        assertEquals("Coolant pump", capture?.displayName)
    }

    @Test
    fun skipForNowOpensTheNewMachineDetailEmpty() = runTest(dispatcher) {
        val navigator = EdgeNavigator()
        val repository = FakeMemoryRepository()
        val vm = machinesViewModel(repository, navigator = navigator)
        advanceUntilIdle()

        vm.openAddMachine()
        vm.onMachineIdChange("P-102")
        vm.addMachine()
        advanceUntilIdle()

        vm.skipInitialRecords()
        assertEquals(EdgeRoute.MachineDetail("p102"), navigator.current)
        assertNull(vm.creation.value.createdId) // form reset
    }

    @Test
    fun machineDefinitionRecordIsExcludedFromActivityTimelineButDerivesAsset() {
        val definition = memory(
            "def-1", title = "Coolant pump", subjectKey = "p102/machine",
            type = MemoryType.NOTE,
        ).copy(metadata = mapOf(AssetModel.MACHINE_METADATA_KEY to AssetModel.MACHINE_METADATA_VALUE))

        assertTrue(AssetModel.isMachineDefinition(definition))
        // Excluded from activity records.
        assertTrue(AssetModel.recordsFor(listOf(definition), "p102").isEmpty())
        // Still derives the asset so the machine exists in the list.
        val assets = AssetModel.deriveAssets(listOf(definition))
        assertEquals(listOf("p102"), assets.map { it.namespace })
        assertEquals(0, assets.single().recordCount)
        assertEquals("Coolant pump", assets.single().representativeTitle)
    }

    private companion object {
        fun memory(
            id: String,
            title: String = id,
            content: String = "content-$id",
            type: MemoryType = MemoryType.NOTE,
            subjectKey: String? = null,
            updatedAt: Long = 1_000L,
            syncState: MemorySyncState = MemorySyncState.LOCAL,
            syncDecision: SyncDecision = SyncDecision.LOCAL_ONLY,
        ): Memory = Memory(
            memoryId = id,
            title = title,
            content = content,
            chunkId = null,
            source = "TEST",
            type = type,
            tags = emptyList(),
            createdAt = updatedAt,
            updatedAt = updatedAt,
            origin = MemoryOrigin.LOCAL,
            syncDecision = syncDecision,
            syncState = syncState,
            sensitivity = MemorySensitivity.STANDARD,
            importance = 0,
            version = 1,
            contentHash = "hash-$id",
            subjectKey = subjectKey,
            supersedes = null,
            tombstone = false,
            metadata = emptyMap(),
        )

        fun conflict(
            id: String,
            localMemoryId: String?,
            detectedAt: Long,
        ): Conflict = Conflict(
            conflictId = id,
            subjectKey = "p101/seal",
            localMemoryId = localMemoryId,
            incomingMemoryId = "cloud-$id",
            localTitle = "Local evidence",
            incomingTitle = "Cloud evidence",
            localContent = "local",
            incomingContent = "cloud",
            localVersion = 1,
            incomingVersion = 2,
            localContentHash = "local-hash",
            incomingContentHash = "cloud-hash",
            localOrigin = "LOCAL",
            incomingOrigin = "CLOUD",
            localAuthority = null,
            incomingAuthority = "OEM",
            detectedAt = detectedAt,
            reason = "PULL_CONFLICT",
            state = ConflictResolutionState.UNRESOLVED,
        )
    }
}

/**
 * Regression test for the "Preparing..." hang bug.
 * The CreateRecordViewModel must initialize with Ready(null) to show the form immediately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateRecordViewModelInitializationTest {

    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeMemoryRepository : MemoryRepository {
        override suspend fun create(input: CreateMemoryInput): Memory =
            Memory(
                memoryId = "mem-" + input.title.hashCode().toString(16),
                title = input.title,
                content = input.content,
                chunkId = null,
                source = input.source,
                type = input.type,
                tags = input.tags,
                createdAt = 0L,
                updatedAt = 0L,
                origin = MemoryOrigin.LOCAL,
                syncDecision = input.userSyncChoice ?: SyncDecision.LOCAL_ONLY,
                syncState = MemorySyncState.LOCAL,
                sensitivity = input.sensitivity,
                importance = input.importance,
                version = 1,
                contentHash = input.content.hashCode().toString(16),
                subjectKey = input.subjectKey,
                supersedes = null,
                tombstone = false,
                metadata = input.metadata,
            )
        override suspend fun createAll(
            inputs: List<CreateMemoryInput>,
            onPhase: (MemoryWritePhase) -> Unit,
        ) = error("not used")
        override suspend fun update(memory: Memory) = error("not used")
        override suspend fun delete(memoryId: String) = error("not used")
        override suspend fun get(memoryId: String): Memory? = null
        override suspend fun list(): List<Memory> = emptyList()
        override suspend fun search(query: String, limit: Int): List<RetrievedMemory> = emptyList()
        override suspend fun count(): Long = 0L
    }

    @Test
    fun createRecordViewModelInitializesWithReadyNullShowsFormImmediately() = runTest(dispatcher) {
        val navigator = EdgeNavigator()
        val viewModel = CreateRecordViewModel(
            createMemory = CreateMemoryUseCase(FakeMemoryRepository()),
            navigator = navigator,
        )

        // The UI state should be Ready(null) immediately, not Loading
        val initialState = viewModel.uiState.value
        assertEquals(LoadableState.Ready<Memory?>(null), initialState.data)
        assertFalse(initialState.data is LoadableState.Loading)
        assertFalse(initialState.data is LoadableState.Failed)

        // Form fields should be empty but validatable
        assertEquals("", initialState.title)
        assertEquals("", initialState.content)
        assertEquals("", initialState.subject)
        assertEquals(MemoryType.NOTE, initialState.type)
        assertNull(initialState.error)
        assertFalse(initialState.submitting)
    }

    @Test
    fun createRecordViewModelSubmitCreatesRecordAndTransitionsToConfirmation() = runTest(dispatcher) {
        val repository = FakeMemoryRepository()
        val navigator = EdgeNavigator()
        val viewModel = CreateRecordViewModel(
            createMemory = CreateMemoryUseCase(repository),
            navigator = navigator,
        )

        // Fill in valid form data
        viewModel.onSubjectChange("P-101")
        viewModel.onTitleChange("Test Record")
        viewModel.onContentChange("Test content")
        viewModel.onTypeChange(MemoryType.NOTE)
        viewModel.onSyncChoiceChange(SyncDecision.LOCAL_ONLY)

        // Submit
        viewModel.submit()
        advanceUntilIdle()

        // Should have created record and transitioned to confirmation
        val finalState = viewModel.uiState.value
        assertTrue(finalState.data is LoadableState.Ready<*>)
        val ready = finalState.data as LoadableState.Ready<*>
        assertNotNull(ready.value)
        assertEquals("Test Record", (ready.value as Memory).title)
        assertEquals("p-101/note", (ready.value as Memory).subjectKey)
    }

    @Test
    fun documentPickerCancelIsANoOpKeepingCurrentState() = runTest(dispatcher) {
        val viewModel = CreateRecordViewModel(
            createMemory = CreateMemoryUseCase(FakeMemoryRepository()),
            navigator = EdgeNavigator(),
        )
        viewModel.onSubjectChange("P-101")
        viewModel.onTypeChange(MemoryType.DOCUMENT)

        // The system file picker returns null when the user cancels.
        viewModel.onDocumentPicked(null)
        advanceUntilIdle()

        // Cancel must not error, must not select a document, must not change type.
        assertNull(viewModel.uiState.value.documentUri)
        assertNull(viewModel.uiState.value.error)
        assertEquals(MemoryType.DOCUMENT, viewModel.uiState.value.type)
    }

    @Test
    fun documentSubmitWithoutFileAsksForDocumentOrText() = runTest(dispatcher) {
        val viewModel = CreateRecordViewModel(
            createMemory = CreateMemoryUseCase(FakeMemoryRepository()),
            navigator = EdgeNavigator(),
        )
        viewModel.onSubjectChange("P-101")
        viewModel.onTypeChange(MemoryType.DOCUMENT)
        // No document and no text → invalid, with a document-aware message.
        assertFalse(viewModel.uiState.value.isFormValid)

        viewModel.submit()
        advanceUntilIdle()
        assertEquals(
            "Select a document, or provide a title or description.",
            viewModel.uiState.value.error,
        )
    }

    @Test
    fun documentTypeWithoutFileFallsBackToTextRecordThroughProductionPath() = runTest(dispatcher) {
        val repository = FakeMemoryRepository()
        val viewModel = CreateRecordViewModel(
            createMemory = CreateMemoryUseCase(repository),
            navigator = EdgeNavigator(),
            // No documentReader/ingestDocument: the text-only path must still work.
        )
        viewModel.onSubjectChange("P-101")
        viewModel.onTypeChange(MemoryType.DOCUMENT)
        viewModel.onTitleChange("Torque spec note")
        viewModel.onContentChange("Re-torque casing bolts to 42 Nm.")

        viewModel.submit()
        advanceUntilIdle()

        val created = (viewModel.uiState.value.data as LoadableState.Ready<*>).value as Memory
        assertEquals(MemoryType.DOCUMENT, created.type)
        assertEquals("p-101/document", created.subjectKey)
    }

    @Test
    fun addAnotherResetsTheFormAndKeepsTheSubjectContext() = runTest(dispatcher) {
        val viewModel = CreateRecordViewModel(
            createMemory = CreateMemoryUseCase(FakeMemoryRepository()),
            navigator = EdgeNavigator(),
        )
        viewModel.onSubjectChange("P-101")
        viewModel.onTitleChange("One")
        viewModel.onContentChange("Body")
        viewModel.submit()
        advanceUntilIdle()

        viewModel.onAddAnother()

        val state = viewModel.uiState.value
        assertNull(state.createdMemory)
        assertEquals(LoadableState.Ready<Memory?>(null), state.data)
        assertEquals("", state.title)
        assertEquals("", state.content)
        assertNull(state.documentUri)
    }

    // ── Machine-scoped capture (Add Machine → initial records) ──

    @Test
    fun lockedCapturePrefillsSubjectAndDefaultsToObservation() = runTest(dispatcher) {
        val viewModel = CreateRecordViewModel(
            createMemory = CreateMemoryUseCase(FakeMemoryRepository()),
            navigator = EdgeNavigator(),
            initialSubject = "p102",
            machineName = "Coolant pump",
        )
        val state = viewModel.uiState.value
        assertTrue(state.subjectLocked)
        assertEquals("p102", state.subject)
        assertEquals("Coolant pump", state.machineName)
        assertEquals(MemoryType.OBSERVATION, state.type)
        // Subject is already satisfied, but a title/content is still required.
        assertFalse(state.isFormValid)
        viewModel.onTitleChange("Vibration check")
        assertTrue(viewModel.uiState.value.isFormValid)
    }

    @Test
    fun lockedCaptureIgnoresSubjectEditsAndWritesUnderTheMachineNamespace() = runTest(dispatcher) {
        val repository = FakeMemoryRepository()
        val viewModel = CreateRecordViewModel(
            createMemory = CreateMemoryUseCase(repository),
            navigator = EdgeNavigator(),
            initialSubject = "p102",
        )
        // Attempting to retarget the subject must be ignored (isolation guard).
        viewModel.onSubjectChange("P-101")
        assertEquals("p102", viewModel.uiState.value.subject)

        viewModel.onTitleChange("Bearing noise")
        viewModel.submit()
        advanceUntilIdle()

        val created = (viewModel.uiState.value.data as LoadableState.Ready<*>).value as Memory
        assertEquals("p102/observation", created.subjectKey)
    }

    @Test
    fun lockedCaptureFinishOpensTheMachineAndClearsContext() = runTest(dispatcher) {
        val navigator = EdgeNavigator()
        navigator.openCreateRecordForMachine("p102", "Coolant pump")
        val repository = FakeMemoryRepository()
        val viewModel = CreateRecordViewModel(
            createMemory = CreateMemoryUseCase(repository),
            navigator = navigator,
            initialSubject = "p102",
        )
        viewModel.onTitleChange("First note")
        viewModel.submit()
        advanceUntilIdle()

        viewModel.onCreatedAcknowledged()
        assertEquals(EdgeRoute.MachineDetail("p102"), navigator.current)
        assertNull(navigator.machineCapture.value)
    }

    @Test
    fun lockedCaptureCancelClearsContext() = runTest(dispatcher) {
        val navigator = EdgeNavigator()
        navigator.openCreateRecordForMachine("p102", "Coolant pump")
        val viewModel = CreateRecordViewModel(
            createMemory = CreateMemoryUseCase(FakeMemoryRepository()),
            navigator = navigator,
            initialSubject = "p102",
        )
        viewModel.cancel()
        assertNull(navigator.machineCapture.value)
    }
}
