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
import com.example.EdgeMemo.domain.memory.GetMemoryUseCase
import com.example.EdgeMemo.domain.memory.ListMemoriesUseCase
import com.example.EdgeMemo.domain.memory.MemoryRepository
import com.example.EdgeMemo.domain.memory.MemoryWritePhase
import com.example.EdgeMemo.presentation.machines.AssetModel.AssetRecordCategory
import com.example.EdgeMemo.presentation.shell.EdgeNavigator
import com.example.EdgeMemo.presentation.shell.EdgeTab
import com.example.EdgeMemo.presentation.shell.EdgeRoute
import com.example.EdgeMemo.presentation.shell.LoadableState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UI Phase 3 — asset workspace ViewModel over boundary fakes implementing the
 * EXISTING domain interfaces (no production fake exists; the container keeps
 * the real Qdrant wiring).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetWorkspacePhase3Test {

    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepo(
        val records: MutableList<Memory> = mutableListOf(),
        var failing: Boolean = false,
    ) : MemoryRepository {
        var lastInput: CreateMemoryInput? = null
        override suspend fun create(input: CreateMemoryInput): Memory {
            if (failing) throw IllegalStateException("shard write unavailable")
            lastInput = input
            val record = Memory(
                memoryId = "made-${records.size}",
                title = input.title,
                content = input.content,
                chunkId = null,
                source = input.source,
                type = input.type,
                tags = input.tags,
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_000_001L,
                origin = MemoryOrigin.LOCAL,
                syncDecision = SyncDecision.LOCAL_ONLY,
                syncState = MemorySyncState.LOCAL,
                sensitivity = MemorySensitivity.STANDARD,
                importance = input.importance,
                version = 1,
                contentHash = "hash-made-${records.size}",
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
        ) = error("not used")

        override suspend fun update(memory: Memory) = error("not used")
        override suspend fun delete(memoryId: String) = error("not used")
        override suspend fun get(memoryId: String): Memory? {
            if (failing) throw IllegalStateException("local memory read failed")
            return records.firstOrNull { it.memoryId == memoryId }
        }
        override suspend fun list(): List<Memory> {
            if (failing) throw IllegalStateException("local memory read failed")
            return records.toList()
        }

        override suspend fun search(query: String, limit: Int): List<RetrievedMemory> = emptyList()
        override suspend fun count(): Long = records.size.toLong()
    }

    private class FakeConflictRepo(val conflicts: MutableList<Conflict> = mutableListOf()) : ConflictRepository {
        override suspend fun list() = conflicts.toList()
        override suspend fun get(conflictId: String) = conflicts.firstOrNull { it.conflictId == conflictId }
        override suspend fun countUnresolved() =
            conflicts.count { it.state == ConflictResolutionState.UNRESOLVED }.toLong()
    }

    private fun rec(
        id: String,
        title: String = id,
        type: MemoryType = MemoryType.NOTE,
        subjectKey: String? = null,
        updatedAt: Long = 1_700_000_000_000L,
        syncState: MemorySyncState = MemorySyncState.LOCAL,
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
        tombstone = false,
        metadata = emptyMap(),
    )

    private fun conflict(id: String, subject: String): Conflict = Conflict(
        conflictId = id,
        subjectKey = subject,
        localMemoryId = "local",
        incomingMemoryId = "incoming",
        localTitle = "local",
        incomingTitle = "incoming",
        localContent = "a",
        incomingContent = "b",
        localVersion = 2,
        incomingVersion = 3,
        localContentHash = "aa",
        incomingContentHash = "bb",
        localOrigin = "LOCAL",
        incomingOrigin = "CLOUD",
        localAuthority = null,
        incomingAuthority = "OEM",
        detectedAt = 1_700_000_000_000L,
        reason = "PULL_CONFLICT",
        state = ConflictResolutionState.UNRESOLVED,
    )

    private fun vm(
        namespace: String,
        repo: FakeRepo,
        conflicts: FakeConflictRepo = FakeConflictRepo(),
        navigator: EdgeNavigator = EdgeNavigator(),
    ) = Triple(
        MachineDetailViewModel(
            namespace = namespace,
            listMemories = ListMemoriesUseCase(repo),
            listConflicts = ListConflictsUseCase(conflicts),
            createMemory = CreateMemoryUseCase(repo),
            navigator = navigator,
        ),
        repo,
        navigator,
    )

    // ── loading / content / empty / error ────────────────────────────────

    @Test
    fun assetWorkspaceLoadsRealRecordsWithDerivedCategories() = runTest(dispatcher) {
        val repo = FakeRepo(
            mutableListOf(
                rec("a", title = "Seal weep", type = MemoryType.OBSERVATION, subjectKey = "p101/seal", updatedAt = 300),
                rec("b", title = "Seal replaced", type = MemoryType.REPAIR, subjectKey = "p101/seal", updatedAt = 200),
                rec("c", title = "Restart steps", type = MemoryType.PROCEDURE, subjectKey = "p101/op", updatedAt = 100),
                rec("d", title = "Unrelated", type = MemoryType.NOTE), // no subject: not an asset record
                rec("e", title = "Other asset", type = MemoryType.EVENT, subjectKey = "line-b/x", updatedAt = 50),
            ),
        )
        val (machine, _, _) = vm("p101", repo)
        advanceUntilIdle()

        val state = machine.uiState.value
        val data = (state.data as LoadableState.Ready).value
        assertEquals(listOf("a", "b", "c"), data.records.map { it.memoryId })
        assertEquals(
            mapOf(
                AssetRecordCategory.OBSERVATIONS to 1,
                AssetRecordCategory.MAINTENANCE to 1,
                AssetRecordCategory.PROCEDURES to 1,
            ),
            data.countsByCategory,
        )
        assertEquals(3, data.asset.recordCount)
        // maintenanceCount = existing deriveAssets semantics: maintenance-relevant
        // types (REPAIR + OBSERVATION + PROCEDURE + EVENT).
        assertEquals(3, data.asset.maintenanceCount)
        assertNull(state.data?.let { (it as? LoadableState.Failed) })
    }

    @Test
    fun chronologicalOrderIsDeterministicIncludingTies() = runTest(dispatcher) {
        val repo = FakeRepo(
            mutableListOf(
                // same timestamp: memoryId tie-break (ascending), newest-first overall
                rec("zz", subjectKey = "p101/x", updatedAt = 500),
                rec("aa", subjectKey = "p101/x", updatedAt = 500),
                rec("mid", subjectKey = "p101/x", updatedAt = 400),
            ),
        )
        val (machine, _, _) = vm("p101", repo)
        advanceUntilIdle()
        val data = (machine.uiState.value.data as LoadableState.Ready).value
        assertEquals(listOf("aa", "zz", "mid"), data.records.map { it.memoryId })
    }

    @Test
    fun unknownAssetIsHonestEmptyNotError() = runTest(dispatcher) {
        val (machine, _, _) = vm("ghost", FakeRepo())
        advanceUntilIdle()
        val data = (machine.uiState.value.data as LoadableState.Ready).value
        assertTrue(data.records.isEmpty())
        assertEquals(0, data.asset.recordCount)
    }

    @Test
    fun readFailureSurfacesErrorAndRefreshRecovers() = runTest(dispatcher) {
        val repo = FakeRepo(mutableListOf(rec("a", subjectKey = "p101/x")), failing = true)
        val (machine, _, _) = vm("p101", repo)
        advanceUntilIdle()
        assertTrue(machine.uiState.value.data is LoadableState.Failed)

        repo.failing = false
        machine.refresh()
        advanceUntilIdle()
        val data = (machine.uiState.value.data as LoadableState.Ready).value
        assertEquals(1, data.records.size)
    }

    // ── conflicts + sync (from real domain state) ────────────────────────

    @Test
    fun conflictIndicatorComesFromRealConflictStoreFilteredByNamespace() = runTest(dispatcher) {
        val repo = FakeRepo(mutableListOf(rec("a", subjectKey = "p101/seal")))
        val conflicts = FakeConflictRepo(
            mutableListOf(
                conflict("c-p101", "p101/seal"),
                conflict("c-other", "line-b/thing"),
            ),
        )
        val (machine, _, _) = vm("p101", repo, conflicts)
        advanceUntilIdle()
        val data = (machine.uiState.value.data as LoadableState.Ready).value
        assertEquals(1L, data.conflictCount)
        assertEquals(listOf("c-p101"), data.conflicts.map { it.conflictId })
        assertEquals(1L, data.asset.unresolvedConflictCount)
    }

    @Test
    fun syncCountsReflectRealRecordStatesOnly() = runTest(dispatcher) {
        val repo = FakeRepo(
            mutableListOf(
                rec("a", subjectKey = "p101/x", syncState = MemorySyncState.PENDING),
                rec("b", subjectKey = "p101/x", syncState = MemorySyncState.FAILED),
                rec("c", subjectKey = "p101/x", syncState = MemorySyncState.SYNCED),
            ),
        )
        val (machine, _, _) = vm("p101", repo)
        advanceUntilIdle()
        val data = (machine.uiState.value.data as LoadableState.Ready).value
        assertEquals(1, data.pendingSyncRecords)
        assertEquals(1, data.failedSyncRecords)
    }

    // ── category filter over already-loaded bounded records ──────────────

    @Test
    fun categoryFilterIsPresentationOnlyOverLoadedRecords() = runTest(dispatcher) {
        val repo = FakeRepo(
            mutableListOf(
                rec("a", type = MemoryType.OBSERVATION, subjectKey = "p101/x"),
                rec("b", type = MemoryType.REPAIR, subjectKey = "p101/x"),
            ),
        )
        val (machine, _, _) = vm("p101", repo)
        advanceUntilIdle()
        assertEquals(2, machine.uiState.value.visibleRecords.size)

        machine.setCategoryFilter(AssetRecordCategory.MAINTENANCE)
        assertEquals(listOf("b"), machine.uiState.value.visibleRecords.map { it.memoryId })

        machine.setCategoryFilter(null)
        assertEquals(2, machine.uiState.value.visibleRecords.size)
    }

    // ── add record through the PRODUCTION use case boundary ──────────────

    @Test
    fun addRecordCreatesAssetAssociatedMemoryThroughCreateMemoryUseCase() = runTest(dispatcher) {
        val repo = FakeRepo()
        val (machine, _, _) = vm("p101", repo)
        advanceUntilIdle()

        machine.openComposer()
        machine.onComposerTitleChange("Seal weep again")
        machine.onComposerContentChange("Observed weeping at the gland after 200h.")
        machine.onComposerTypeChange(MemoryType.OBSERVATION)
        machine.onComposerSyncChoice(SyncDecision.LOCAL_ONLY)
        machine.submitComposer()
        advanceUntilIdle()

        val input = repo.lastInput!!
        assertEquals("Seal weep again", input.title)
        assertEquals(MemoryType.OBSERVATION, input.type)
        assertEquals("p101/observation", input.subjectKey)
        assertEquals(SyncDecision.LOCAL_ONLY, input.userSyncChoice)

        // Refreshed view contains the new record through the normal read path.
        val state = machine.uiState.value
        assertEquals("Saved to P101.", state.composer.createdMessage)
        val data = (state.data as LoadableState.Ready).value
        assertEquals(listOf("made-0"), data.records.map { it.memoryId })
    }

    @Test
    fun addRecordValidationAndDomainErrorsSurfaceWithoutWrites() = runTest(dispatcher) {
        val repo = FakeRepo()
        val (machine, _, _) = vm("p101", repo)
        advanceUntilIdle()

        machine.openComposer()
        machine.submitComposer() // blank title AND content
        assertEquals("Add a title or a few words about what happened.", machine.uiState.value.composer.error)
        assertNull(repo.lastInput)

        machine.onComposerTitleChange("ok")
        repo.failing = true
        machine.submitComposer()
        advanceUntilIdle()
        assertEquals("shard write unavailable", machine.uiState.value.composer.error)
        assertTrue(machine.uiState.value.composer.open) // composer stays for retry
    }

    // ── navigation ───────────────────────────────────────────────────────

    @Test
    fun recordAndAskNavigationUseTheExistingRoutes() = runTest(dispatcher) {
        val navigator = EdgeNavigator()
        navigator.select(EdgeTab.MACHINES)
        val repo = FakeRepo(mutableListOf(rec("a", subjectKey = "p101/x")))
        val (machine, _, nav) = vm("p101", repo, navigator = navigator)
        advanceUntilIdle()

        machine.openRecord("a")
        assertEquals(EdgeRoute.RecordDetail("a"), nav.current)

        nav.pop()
        machine.askAboutAsset()
        assertEquals(EdgeRoute.Ask, nav.current)
        assertEquals("p101", nav.askAsset.value)
    }

    // ── record detail VM ─────────────────────────────────────────────────

    @Test
    fun recordDetailResolvesRealRecordMissingIsHonestAndNoFake() = runTest(dispatcher) {
        val repo = FakeRepo(mutableListOf(rec("a", title = "Seal note", subjectKey = "p101/x")))
        val found = RecordDetailViewModel("a", GetMemoryUseCase(repo))
        advanceUntilIdle()
        val ready = found.uiState.value as LoadableState.Ready
        assertEquals("Seal note", ready.value?.title)

        val missing = RecordDetailViewModel("gone", GetMemoryUseCase(repo))
        advanceUntilIdle()
        val gone = missing.uiState.value as LoadableState.Ready
        assertNull(gone.value)

        repo.failing = true
        val broken = RecordDetailViewModel("a", GetMemoryUseCase(repo))
        advanceUntilIdle()
        assertNotNull(broken.uiState.value as? LoadableState.Failed)
    }
}
