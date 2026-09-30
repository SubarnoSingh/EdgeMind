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

        override suspend fun create(input: CreateMemoryInput): Memory {
            createCalls += 1
            createGate?.await()
            if (failWrite) error("local write failed")
            lastInput = input
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
            ).also(records::add)
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
}
