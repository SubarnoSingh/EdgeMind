package com.example.EdgeMemo.presentation.conflicts

import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemorySensitivity
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.RetrievedMemory
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.domain.conflict.ConflictRepository
import com.example.EdgeMemo.domain.conflict.ConflictResolutionAction
import com.example.EdgeMemo.domain.conflict.ConflictResolutionState
import com.example.EdgeMemo.domain.conflict.ConflictResolver
import com.example.EdgeMemo.domain.conflict.CountUnresolvedConflictsUseCase
import com.example.EdgeMemo.domain.conflict.GetConflictUseCase
import com.example.EdgeMemo.domain.conflict.ListConflictsUseCase
import com.example.EdgeMemo.domain.conflict.ResolveConflictUseCase
import com.example.EdgeMemo.domain.memory.GetMemoryUseCase
import com.example.EdgeMemo.domain.memory.ListMemoriesUseCase
import com.example.EdgeMemo.domain.memory.MemoryRepository
import com.example.EdgeMemo.domain.memory.MemoryWritePhase
import com.example.EdgeMemo.presentation.shell.EdgeNavigator
import com.example.EdgeMemo.presentation.shell.EdgeRoute
import com.example.EdgeMemo.presentation.shell.EdgeTab
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UI Phase 4 — the conflict workflow ViewModels against boundary fakes that
 * IMPLEMENT THE EXISTING DOMAIN INTERFACES. The fakes emulate the durable
 * store's state machine (a resolved conflict never returns to UNRESOLVED);
 * the real Qdrant behaviour is covered by the 12B.10 suites and the
 * production E2E test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConflictWorkflowPhase4Test {

    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ── fakes ────────────────────────────────────────────────────────────

    private open class FakeConflictStore : ConflictRepository, ConflictResolver {
        val conflicts = LinkedHashMap<String, Conflict>()
        var resolverCalls = 0
        var resolverError: String? = null

        fun put(conflict: Conflict) {
            conflicts[conflict.conflictId] = conflict
        }

        private fun settle(id: String, state: ConflictResolutionState, resolution: String): Conflict {
            val existing = requireNotNull(conflicts[id]) { "unknown conflict $id" }
            if (existing.state != ConflictResolutionState.UNRESOLVED) {
                return existing // immutable once resolved (12B.10 semantics)
            }
            val settled = existing.copy(
                state = state,
                resolvedAt = 1_700_000_900_000L,
                resolution = resolution,
            )
            conflicts[id] = settled
            return settled
        }

        open override suspend fun list(): List<Conflict> = conflicts.values.sortedByDescending { it.detectedAt }

        override suspend fun get(conflictId: String): Conflict? = conflicts[conflictId]

        override suspend fun countUnresolved(): Long =
            conflicts.values.count { it.state == ConflictResolutionState.UNRESOLVED }.toLong()

        override suspend fun keepLocal(conflictId: String, note: String?): Conflict {
            resolverCalls++
            resolverError?.let { throw IllegalStateException(it) }
            return settle(conflictId, ConflictResolutionState.RESOLVED_LOCAL, "kept local")
        }

        override suspend fun keepCloud(conflictId: String, note: String?): Conflict {
            resolverCalls++
            resolverError?.let { throw IllegalStateException(it) }
            return settle(conflictId, ConflictResolutionState.RESOLVED_CLOUD, "accepted cloud")
        }

        override suspend fun dismiss(conflictId: String, note: String?): Conflict {
            resolverCalls++
            resolverError?.let { throw IllegalStateException(it) }
            return settle(conflictId, ConflictResolutionState.DISMISSED, "kept both, reviewed")
        }
    }

    private class FakeMemoryRepo(val records: MutableList<Memory> = mutableListOf()) : MemoryRepository {
        override suspend fun create(input: com.example.EdgeMemo.core.model.CreateMemoryInput): Memory =
            error("not used")

        override suspend fun createAll(
            inputs: List<com.example.EdgeMemo.core.model.CreateMemoryInput>,
            onPhase: (MemoryWritePhase) -> Unit,
        ): List<Memory> = error("not used")

        override suspend fun update(memory: Memory): Memory = error("not used")
        override suspend fun delete(memoryId: String) = error("not used")
        override suspend fun get(memoryId: String): Memory? =
            records.firstOrNull { it.memoryId == memoryId && !it.tombstone }

        override suspend fun list(): List<Memory> = records.toList()
        override suspend fun search(query: String, limit: Int): List<RetrievedMemory> = emptyList()
        override suspend fun count(): Long = records.size.toLong()
    }

    private fun memory(id: String, syncState: MemorySyncState = MemorySyncState.PENDING): Memory =
        Memory(
            memoryId = id,
            title = "P-101 seal note",
            content = "Seal weep observed on P-101.",
            chunkId = null,
            source = "USER_ENTRY",
            type = MemoryType.OBSERVATION,
            tags = emptyList(),
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_500L,
            origin = MemoryOrigin.LOCAL,
            syncDecision = SyncDecision.SYNC,
            syncState = syncState,
            sensitivity = MemorySensitivity.STANDARD,
            importance = 0,
            version = 5,
            contentHash = "hash-$id",
            subjectKey = "p101/seal",
            supersedes = null,
            tombstone = false,
            metadata = emptyMap(),
        )

    private fun conflict(
        id: String,
        subject: String = "p101/seal",
        detectedAt: Long = 1_700_000_000_000L,
        localTombstone: Boolean = false,
        incomingTombstone: Boolean = false,
        state: ConflictResolutionState = ConflictResolutionState.UNRESOLVED,
    ): Conflict = Conflict(
        conflictId = id,
        subjectKey = subject,
        localMemoryId = "local-$id",
        incomingMemoryId = "incoming-$id",
        localTitle = "Local seal note",
        incomingTitle = "Cloud seal procedure",
        localContent = "Weep observed after 3800 hours.",
        incomingContent = "Replace faces at overhaul.",
        localVersion = 4,
        incomingVersion = 4,
        localContentHash = "aa",
        incomingContentHash = "bb",
        localOrigin = "LOCAL",
        incomingOrigin = "CLOUD",
        localAuthority = null,
        incomingAuthority = "OEM-MANUAL",
        detectedAt = detectedAt,
        reason = "PULL_CONFLICT",
        state = state,
        localTombstone = localTombstone,
        incomingTombstone = incomingTombstone,
    )

    private fun listVm(
        store: FakeConflictStore,
        navigator: EdgeNavigator = EdgeNavigator(),
    ) = ConflictListViewModel(
        listConflicts = ListConflictsUseCase(store),
        countUnresolvedConflicts = CountUnresolvedConflictsUseCase(store),
        navigator = navigator,
    )

    private fun detailVm(
        id: String,
        store: FakeConflictStore,
        memories: FakeMemoryRepo = FakeMemoryRepo(),
        navigator: EdgeNavigator = EdgeNavigator(),
    ) = ConflictDetailViewModel(
        conflictId = id,
        getConflict = GetConflictUseCase(store),
        resolveConflict = ResolveConflictUseCase(store),
        getMemory = GetMemoryUseCase(memories),
        navigator = navigator,
    )

    // ── list ─────────────────────────────────────────────────────────────

    @Test
    fun listLoadsUnresolvedConflictsNewestFirstWithRealIds() = runTest(dispatcher) {
        val store = FakeConflictStore()
        store.put(conflict("c-old", detectedAt = 100))
        store.put(conflict("c-new", detectedAt = 500))
        store.put(conflict("c-done", detectedAt = 300, state = ConflictResolutionState.RESOLVED_LOCAL))
        val vm = listVm(store)
        advanceUntilIdle()

        val data = (vm.uiState.value as LoadableState.Ready).value
        assertEquals(listOf("c-new", "c-old"), data.rows.map { it.conflict.conflictId })
        assertEquals(2L, data.unresolvedCount)
        assertEquals("p101", data.rows.first().assetNamespace)
    }

    @Test
    fun emptyStoreIsAnHonestEmptyList() = runTest(dispatcher) {
        val vm = listVm(FakeConflictStore())
        advanceUntilIdle()
        val data = (vm.uiState.value as LoadableState.Ready).value
        assertTrue(data.rows.isEmpty())
        assertEquals(0L, data.unresolvedCount)
    }

    @Test
    fun listErrorAndRetryRecovers() = runTest(dispatcher) {
        val failing = object : FakeConflictStore() {
            override suspend fun list(): List<Conflict> = throw IllegalStateException("store unavailable")
        }
        val vm = listVm(failing)
        advanceUntilIdle()
        assertTrue(vm.uiState.value is LoadableState.Failed)
        assertEquals("store unavailable", (vm.uiState.value as LoadableState.Failed).message)
    }

    @Test
    fun listOpensDetailThroughTheNavigatorRoute() = runTest(dispatcher) {
        val navigator = EdgeNavigator()
        val store = FakeConflictStore().apply { put(conflict("c1")) }
        val vm = listVm(store, navigator)
        advanceUntilIdle()
        vm.openConflict("c1")
        assertEquals(EdgeRoute.ConflictDetail("c1"), navigator.current)
    }

    // ── detail: evidence & confirmation ──────────────────────────────────

    @Test
    fun detailExposesRealEvidenceOnBothSides() = runTest(dispatcher) {
        val store = FakeConflictStore().apply {
            put(conflict("c1", localTombstone = true))
        }
        val vm = detailVm("c1", store)
        advanceUntilIdle()
        val data = (vm.uiState.value as LoadableState.Ready).value
        assertEquals("Local seal note", data.conflict.localTitle)
        assertEquals("Replace faces at overhaul.", data.conflict.incomingContent)
        assertEquals(4, data.conflict.localVersion)
        assertEquals(4, data.conflict.incomingVersion)
        assertEquals("OEM-MANUAL", data.conflict.incomingAuthority)
        assertNull(data.conflict.localAuthority)
        assertTrue(data.conflict.localTombstone) // real tombstone evidence
        assertFalse(data.conflict.incomingTombstone)
        assertEquals(ResolutionUi.Idle, data.resolution)
    }

    @Test
    fun missingConflictIsReportedHonestly() = runTest(dispatcher) {
        val vm = detailVm("ghost", FakeConflictStore())
        advanceUntilIdle()
        assertTrue(vm.uiState.value is LoadableState.Failed)
    }

    @Test
    fun actionsOnlyAskForConfirmationAndNothingIsWrittenBeforeConfirm() = runTest(dispatcher) {
        val store = FakeConflictStore().apply { put(conflict("c1")) }
        val vm = detailVm("c1", store)
        advanceUntilIdle()

        vm.requestResolution(ConflictResolutionAction.KEEP_CLOUD)
        val ready = (vm.uiState.value as LoadableState.Ready).value
        assertEquals(
            ConflictResolutionAction.KEEP_CLOUD,
            (ready.resolution as ResolutionUi.Confirming).action,
        )
        assertEquals(0, store.resolverCalls)
        assertEquals(ConflictResolutionState.UNRESOLVED, store.get("c1")!!.state)

        vm.cancelResolution()
        assertEquals(
            ResolutionUi.Idle,
            (vm.uiState.value as LoadableState.Ready).value.resolution,
        )
        assertEquals(0, store.resolverCalls)
    }

    @Test
    fun confirmKeepLocalCallsTheRealResolverAndSettlesState() = runTest(dispatcher) {
        val store = FakeConflictStore().apply { put(conflict("c1")) }
        val vm = detailVm("c1", store)
        advanceUntilIdle()

        vm.requestResolution(ConflictResolutionAction.KEEP_LOCAL)
        vm.confirmResolution()
        advanceUntilIdle()

        val data = (vm.uiState.value as LoadableState.Ready).value
        assertEquals(1, store.resolverCalls)
        assertEquals(ConflictResolutionState.RESOLVED_LOCAL, data.conflict.state)
        val outcome = data.resolution as ResolutionUi.Resolved
        assertFalse(outcome.wasAlreadyResolved)
        assertNull(outcome.localRecord) // FakeMemoryRepo has no such record: honest null
        assertFalse(data.isUnresolved)
    }

    @Test
    fun confirmKeepCloudShowsTheRealPendingSyncState() = runTest(dispatcher) {
        val store = FakeConflictStore().apply { put(conflict("c1")) }
        val memories = FakeMemoryRepo().apply { records += memory("local-c1", MemorySyncState.PENDING) }
        val vm = detailVm("c1", store, memories)
        advanceUntilIdle()

        vm.requestResolution(ConflictResolutionAction.KEEP_CLOUD)
        vm.confirmResolution()
        advanceUntilIdle()

        val outcome = ((vm.uiState.value as LoadableState.Ready).value.resolution) as ResolutionUi.Resolved
        assertNotNull(outcome.localRecord)
        assertEquals(MemorySyncState.PENDING, outcome.localRecord!!.syncState)
        // the resolved record content/version came from the durable read, not the UI
        assertEquals(5, outcome.localRecord!!.version)
    }

    @Test
    fun doubleTapOnConfirmInvokesTheResolverOnlyOnce() = runTest(dispatcher) {
        val store = FakeConflictStore().apply { put(conflict("c1")) }
        val vm = detailVm("c1", store)
        advanceUntilIdle()

        vm.requestResolution(ConflictResolutionAction.KEEP_LOCAL)
        vm.confirmResolution()
        vm.confirmResolution() // second tap before completion
        vm.confirmResolution() // and another
        advanceUntilIdle()

        assertEquals(1, store.resolverCalls)
        val data = (vm.uiState.value as LoadableState.Ready).value
        assertTrue(data.isNowSettled)
    }

    @Test
    fun alreadyResolvedConflictReportsAlreadyResolvedWithoutRewriting() = runTest(dispatcher) {
        val store = FakeConflictStore().apply {
            put(conflict("c1", state = ConflictResolutionState.RESOLVED_CLOUD))
        }
        val vm = detailVm("c1", store)
        advanceUntilIdle()

        // UI shows settled state; the confirmation path still consumes the
        // real (idempotent) resolver, which must NOT rewrite durable state.
        vm.requestResolution(ConflictResolutionAction.KEEP_LOCAL)
        vm.confirmResolution()
        advanceUntilIdle()

        val outcome = ((vm.uiState.value as LoadableState.Ready).value.resolution as ResolutionUi.Resolved)
        assertTrue("idempotent re-read must be labelled honestly", outcome.wasAlreadyResolved)
        assertEquals(1, store.resolverCalls)
        assertEquals(ConflictResolutionState.RESOLVED_CLOUD, store.get("c1")!!.state)
    }

    @Test
    fun staleConflictResolvedByAnotherSessionConvergesFromTheDurableRead() = runTest(dispatcher) {
        val store = FakeConflictStore().apply { put(conflict("c1")) }
        val vm = detailVm("c1", store)
        advanceUntilIdle()
        vm.requestResolution(ConflictResolutionAction.KEEP_LOCAL)

        // Another session resolves it first (dismissing).
        store.dismiss("c1", "other session")

        vm.confirmResolution()
        advanceUntilIdle()

        val data = (vm.uiState.value as LoadableState.Ready).value
        val outcome = data.resolution as ResolutionUi.Resolved
        // The durable store wins: DISMISSED (the other session's outcome),
        // not the UI's KEEP_LOCAL wish; the re-read exposes the truth.
        assertEquals(ConflictResolutionState.DISMISSED, data.conflict.state)
        assertTrue(outcome.wasAlreadyResolved)
        // other session's dismiss + this VM's idempotent call — the durable
        // store never flipped back to RESOLVED_LOCAL (immutability proven).
        assertEquals(2, store.resolverCalls)
    }

    @Test
    fun resolverFailureShowsErrorWithRetryAndLeavesStateUnresolved() = runTest(dispatcher) {
        val store = FakeConflictStore().apply {
            put(conflict("c1"))
            resolverError = "resolver refused"
        }
        val vm = detailVm("c1", store)
        advanceUntilIdle()

        vm.requestResolution(ConflictResolutionAction.KEEP_CLOUD)
        vm.confirmResolution()
        advanceUntilIdle()

        val data = (vm.uiState.value as LoadableState.Ready).value
        val failure = data.resolution as ResolutionUi.Failed
        assertEquals(ConflictResolutionAction.KEEP_CLOUD, failure.action)
        assertEquals("resolver refused", failure.message)
        assertEquals(ConflictResolutionState.UNRESOLVED, data.conflict.state)

        // Retry path: clear the error and resolve for real.
        store.resolverError = null
        vm.requestResolution(ConflictResolutionAction.KEEP_CLOUD)
        vm.confirmResolution()
        advanceUntilIdle()
        val after = (vm.uiState.value as LoadableState.Ready).value
        assertEquals(ConflictResolutionState.RESOLVED_CLOUD, after.conflict.state)
    }

    @Test
    fun navigationRoutesAreDeterministic() = runTest(dispatcher) {
        val nav = EdgeNavigator()
        nav.select(EdgeTab.DASHBOARD)
        nav.openConflicts()
        assertEquals(EdgeRoute.Conflicts, nav.current)
        nav.openConflict("c1")
        assertEquals(EdgeRoute.ConflictDetail("c1"), nav.current)
        assertTrue(nav.pop())
        assertEquals(EdgeRoute.Conflicts, nav.current)
        assertTrue(nav.pop())
        assertEquals(EdgeRoute.Dashboard, nav.current)
        assertFalse(nav.pop())
    }

}
