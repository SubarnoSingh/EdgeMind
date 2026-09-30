package com.example.EdgeMemo.presentation.shell

import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemorySensitivity
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.RetrievedMemory
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.sync.SyncSummary
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.domain.conflict.ConflictRepository
import com.example.EdgeMemo.domain.conflict.ConflictResolutionState
import com.example.EdgeMemo.domain.conflict.CountUnresolvedConflictsUseCase
import com.example.EdgeMemo.domain.conflict.ListConflictsUseCase
import com.example.EdgeMemo.domain.memory.CreateMemoryUseCase
import com.example.EdgeMemo.domain.memory.ListMemoriesUseCase
import com.example.EdgeMemo.domain.memory.MemoryRepository
import com.example.EdgeMemo.domain.memory.MemoryWritePhase
import com.example.EdgeMemo.domain.sync.SyncStatusReader
import com.example.EdgeMemo.presentation.dashboard.DashboardViewModel
import com.example.EdgeMemo.presentation.machines.MachineDetailViewModel
import com.example.EdgeMemo.presentation.machines.MachinesViewModel
import com.example.EdgeMemo.presentation.sync.SyncViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UI Phase 1 — ViewModel state machines against boundary fakes. These fakes
 * implement the EXISTING domain interfaces in memory; they replace nothing in
 * production (the container wires only Qdrant-native implementations) and
 * keep these tests fast and deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EdgeShellViewModelsTest {

    private lateinit var mainScheduler: TestDispatcher

    @Before
    fun setUp() {
        mainScheduler = StandardTestDispatcher()
        Dispatchers.setMain(mainScheduler)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── fakes ────────────────────────────────────────────────────────────────

    private class FakeMemoryRepository : MemoryRepository {
        val records = mutableListOf<Memory>()
        var failNext: Boolean = false

        var createError: String? = null
        var lastInput: CreateMemoryInput? = null

        override suspend fun create(input: CreateMemoryInput): Memory {
            createError?.let { throw IllegalStateException(it) }
            lastInput = input
            val record = Memory(
                memoryId = "created-${records.size}",
                title = input.title,
                content = input.content,
                chunkId = null,
                source = input.source,
                type = input.type,
                tags = input.tags,
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_000_000L,
                origin = com.example.EdgeMemo.core.model.MemoryOrigin.LOCAL,
                syncDecision = SyncDecision.LOCAL_ONLY,
                syncState = MemorySyncState.LOCAL,
                sensitivity = com.example.EdgeMemo.core.model.MemorySensitivity.STANDARD,
                importance = input.importance,
                version = 1,
                contentHash = "hash-created-${records.size}",
                subjectKey = input.subjectKey,
                supersedes = null,
                tombstone = false,
                metadata = input.metadata,
            )
            records += record
            return record
        }
        override suspend fun createAll(
            inputs: List<CreateMemoryInput>,
            onPhase: (MemoryWritePhase) -> Unit,
        ): List<Memory> = error("not used in shell tests")

        override suspend fun update(memory: Memory): Memory = error("not used in shell tests")
        override suspend fun delete(memoryId: String) = error("not used in shell tests")
        override suspend fun get(memoryId: String): Memory? = records.firstOrNull { it.memoryId == memoryId }

        override suspend fun list(): List<Memory> {
            if (failNext) throw IllegalStateException("shard read failed")
            return records.toList()
        }

        override suspend fun search(query: String, limit: Int): List<RetrievedMemory> = emptyList()
        override suspend fun count(): Long = records.size.toLong()
    }

    private class FakeConflictRepository(
        var conflicts: List<Conflict> = emptyList(),
    ) : ConflictRepository {
        override suspend fun list(): List<Conflict> = conflicts
        override suspend fun get(conflictId: String): Conflict? =
            conflicts.firstOrNull { it.conflictId == conflictId }

        override suspend fun countUnresolved(): Long =
            conflicts.count { it.state == ConflictResolutionState.UNRESOLVED }.toLong()
    }

    private class FakeSyncStatusReader(
        var summary: SyncSummary = SyncSummary(),
        var failing: Boolean = false,
    ) : SyncStatusReader {
        override suspend fun outboxCounts(): SyncSummary {
            if (failing) throw IllegalStateException("sync store unavailable")
            return summary
        }
    }

    private fun memory(
        id: String,
        title: String = "note $id",
        type: MemoryType = MemoryType.NOTE,
        subjectKey: String? = null,
        updatedAt: Long = 1_700_000_000_000L,
        syncState: MemorySyncState = MemorySyncState.LOCAL,
        tombstone: Boolean = false,
    ): Memory = Memory(
        memoryId = id,
        title = title,
        content = "content of $title",
        chunkId = null,
        source = "TEST",
        type = type,
        tags = emptyList(),
        createdAt = updatedAt - 1000,
        updatedAt = updatedAt,
        origin = MemoryOrigin.LOCAL,
        syncDecision = SyncDecision.LOCAL_ONLY,
        syncState = syncState,
        sensitivity = MemorySensitivity.STANDARD,
        importance = 0,
        version = 1,
        contentHash = "hash-$id",
        subjectKey = subjectKey,
        supersedes = null,
        tombstone = tombstone,
        metadata = emptyMap(),
    )

    private fun conflict(subject: String): Conflict = Conflict(
        conflictId = "conflict-$subject",
        subjectKey = subject,
        localMemoryId = "local",
        incomingMemoryId = "incoming",
        localTitle = "local title",
        incomingTitle = "incoming title",
        localContent = "local content",
        incomingContent = "incoming content",
        localVersion = 1,
        incomingVersion = 2,
        localContentHash = "aa",
        incomingContentHash = "bb",
        localOrigin = "LOCAL",
        incomingOrigin = "CLOUD",
        localAuthority = null,
        incomingAuthority = "OEM",
        detectedAt = 1_700_000_000_000L,
        reason = "TEST",
        state = ConflictResolutionState.UNRESOLVED,
    )

    // ── Dashboard ────────────────────────────────────────────────────────────

    @Test
    fun dashboardDerivesEveryMetricFromRealDomainReads() = runTest(mainScheduler) {
        val repo = FakeMemoryRepository().apply {
            records += memory("a", type = MemoryType.REPAIR, subjectKey = "p101/seal", updatedAt = 500)
            records += memory("b", type = MemoryType.PROCEDURE, subjectKey = "p101/line", updatedAt = 400)
            records += memory("c", type = MemoryType.NOTE, updatedAt = 300)
            records += memory("d", type = MemoryType.OBSERVATION, subjectKey = "line-a", updatedAt = 200)
        }
        val vm = DashboardViewModel(
            listMemories = ListMemoriesUseCase(repo),
            syncStatusReader = FakeSyncStatusReader(SyncSummary(pending = 2, synced = 4, localOnly = 1)),
            countUnresolvedConflicts = CountUnresolvedConflictsUseCase(
                FakeConflictRepository(listOf(conflict("p101/seal"))),
            ),
            navigator = EdgeNavigator(),
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is com.example.EdgeMemo.presentation.shell.LoadableState.Ready)
        val data = (state as com.example.EdgeMemo.presentation.shell.LoadableState.Ready).value
        assertEquals(4, data.totalRecords)
        assertEquals(2, data.assetCount) // p101 + line-a namespaces
        assertEquals(3, data.maintenanceCount) // REPAIR + PROCEDURE + OBSERVATION
        assertEquals(1L, data.unresolvedConflicts)
        assertEquals(2L, data.sync.pending)
        assertEquals(listOf("a", "b", "c", "d"), data.recentRecords.map { it.memoryId })
    }

    @Test
    fun dashboardReadFailureSurfacesErrorAndRecoversOnRetry() = runTest(mainScheduler) {
        val repo = FakeMemoryRepository().apply { failNext = true }
        val vm = DashboardViewModel(
            ListMemoriesUseCase(repo),
            FakeSyncStatusReader(),
            CountUnresolvedConflictsUseCase(FakeConflictRepository()),
            EdgeNavigator(),
        )
        advanceUntilIdle()
        assertTrue(vm.uiState.value is com.example.EdgeMemo.presentation.shell.LoadableState.Failed)

        repo.failNext = false
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.uiState.value is com.example.EdgeMemo.presentation.shell.LoadableState.Ready)
    }

    @Test
    fun dashboardEmptyMemoryIsHonestZeroContentNotError() = runTest(mainScheduler) {
        val vm = DashboardViewModel(
            ListMemoriesUseCase(FakeMemoryRepository()),
            FakeSyncStatusReader(),
            CountUnresolvedConflictsUseCase(FakeConflictRepository()),
            EdgeNavigator(),
        )
        advanceUntilIdle()
        val state = vm.uiState.value as com.example.EdgeMemo.presentation.shell.LoadableState.Ready
        assertEquals(0, state.value.totalRecords)
        assertEquals(0, state.value.assetCount)
    }

    // ── Machines + detail ────────────────────────────────────────────────────

    @Test
    fun machinesDeriveAssetsAndRouteNavigation() = runTest(mainScheduler) {
        val repo = FakeMemoryRepository().apply {
            records += memory("a", subjectKey = "p101/seal", updatedAt = 500)
            records += memory("b", subjectKey = "p101/torque", updatedAt = 400,
                syncState = MemorySyncState.PENDING)
            records += memory("c", subjectKey = null)
            records += memory("d", subjectKey = "line-b", tombstone = true)
        }
        val navigator = EdgeNavigator()
        val vm = MachinesViewModel(
            ListMemoriesUseCase(repo),
            ListConflictsUseCase(FakeConflictRepository(listOf(conflict("p101/seal")))),
            navigator,
        )
        advanceUntilIdle()
        val state = vm.uiState.value as com.example.EdgeMemo.presentation.shell.LoadableState.Ready
        assertEquals(listOf("p101", "line-b").take(1), state.value.map { it.namespace })
        val p101 = state.value.first()
        assertEquals(2, p101.recordCount) // tombstoned line-b excluded; c has no subject
        assertEquals(1, p101.pendingSyncCount)
        assertEquals(1L, p101.unresolvedConflictCount)

        vm.openAsset("p101")
        assertEquals(
            com.example.EdgeMemo.presentation.shell.EdgeRoute.MachineDetail("p101"),
            navigator.current,
        )
    }

    @Test
    fun machineDetailGroupsRecordsAndTombstoneGuards() = runTest(mainScheduler) {
        val repo = FakeMemoryRepository().apply {
            records += memory("a", title = "Seal log", subjectKey = "p101/seal", updatedAt = 500)
            records += memory("b", title = "Torque", subjectKey = "p101/torque", updatedAt = 400)
            records += memory("c", title = "Other", subjectKey = "line-x", updatedAt = 300)
        }
        val vm = MachineDetailViewModel(
            namespace = "p101",
            listMemories = ListMemoriesUseCase(repo),
            listConflicts = ListConflictsUseCase(FakeConflictRepository()),
            createMemory = CreateMemoryUseCase(repo),
            navigator = EdgeNavigator(),
        )
        advanceUntilIdle()
        val state = vm.uiState.value.data as com.example.EdgeMemo.presentation.shell.LoadableState.Ready
        assertEquals(listOf("a", "b"), state.value.records.map { it.memoryId })
        assertEquals(2, state.value.asset.recordCount)
    }

    @Test
    fun machineDetailOfUnknownAssetIsHonestEmpty() = runTest(mainScheduler) {
        val vm = MachineDetailViewModel(
            namespace = "ghost",
            listMemories = ListMemoriesUseCase(FakeMemoryRepository()),
            listConflicts = ListConflictsUseCase(FakeConflictRepository()),
            createMemory = CreateMemoryUseCase(FakeMemoryRepository()),
            navigator = EdgeNavigator(),
        )
        advanceUntilIdle()
        val state = vm.uiState.value.data as com.example.EdgeMemo.presentation.shell.LoadableState.Ready
        assertTrue(state.value.records.isEmpty())
    }

    // ── Shell badges + sync screen ───────────────────────────────────────────

    @Test
    fun shellSyncBadgeReflectsRealOperationStates() = runTest(mainScheduler) {
        val reader = FakeSyncStatusReader()
        val online = MutableStateFlow(true)
        val vm = ShellViewModel(
            EdgeNavigator(), online, reader,
            CountUnresolvedConflictsUseCase(FakeConflictRepository()),
        )
        advanceUntilIdle()
        assertEquals(ShellSyncUi.NO_OPERATIONS, vm.uiState.value.syncUi)

        reader.summary = SyncSummary(pending = 1)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(ShellSyncUi.PENDING, vm.uiState.value.syncUi)

        reader.summary = SyncSummary(pending = 1, syncing = 2)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(ShellSyncUi.SYNCING, vm.uiState.value.syncUi)

        reader.summary = SyncSummary(failed = 3)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(ShellSyncUi.ATTENTION, vm.uiState.value.syncUi)

        // Synced counts NEVER present as syncing.
        reader.summary = SyncSummary(synced = 5)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(ShellSyncUi.SYNCED, vm.uiState.value.syncUi)
    }

    @Test
    fun shellBadgeDegradesHonestlyWhenStoreReadFails() = runTest(mainScheduler) {
        val reader = FakeSyncStatusReader(SyncSummary(pending = 2)).apply { failing = true }
        val vm = ShellViewModel(
            EdgeNavigator(), MutableStateFlow(false), reader,
            CountUnresolvedConflictsUseCase(FakeConflictRepository()),
        )
        advanceUntilIdle()
        assertEquals(ShellSyncUi.NO_OPERATIONS, vm.uiState.value.syncUi)
        assertFalse(vm.uiState.value.isOnline)
    }

    @Test
    fun shellConnectivityFlowsIntoState() = runTest(mainScheduler) {
        val online = MutableStateFlow(false)
        val vm = ShellViewModel(
            EdgeNavigator(), online, FakeSyncStatusReader(),
            CountUnresolvedConflictsUseCase(FakeConflictRepository()),
        )
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isOnline)
        online.value = true
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isOnline)
    }

    @Test
    fun syncViewModelSurfacesConflictsAndSyncNowTrigger() = runTest(mainScheduler) {
        var triggered = 0
        val reader = FakeSyncStatusReader(SyncSummary(pending = 1, synced = 2))
        val repo = FakeMemoryRepository().apply {
            records += memory("a", updatedAt = 100)
        }
        val vm = SyncViewModel(
            syncStatusReader = reader,
            listConflicts = ListConflictsUseCase(
                FakeConflictRepository(listOf(conflict("p101/seal"))),
            ),
            listMemories = ListMemoriesUseCase(repo),
            onSyncNow = { triggered++ },
        )
        advanceUntilIdle()
        val state = vm.uiState.value as com.example.EdgeMemo.presentation.shell.LoadableState.Ready
        assertEquals(1, state.value.conflicts.size)
        assertEquals(1L, state.value.summary.pending)

        vm.syncNow()
        advanceUntilIdle()
        assertEquals(1, triggered)
        assertTrue(vm.uiState.value is com.example.EdgeMemo.presentation.shell.LoadableState.Ready)
    }
}
