package com.example.EdgeMemo.presentation.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application shell navigation destinations.
 *
 * Top-level tabs replace the previous Ask|Memory mode switch; pushed routes
 * (asset detail, record browser, citation detail) stack above the active tab.
 * Deliberately a small explicit state machine instead of a navigation
 * dependency — the app has no Navigation library and adding one is
 * unnecessary for five tabs.
 */
enum class EdgeTab {
    DASHBOARD,
    MACHINES,
    ASK,
    SYNC,
    SETTINGS,
}

/** Full-screen routes pushed on top of a tab. */
sealed interface EdgeRoute {
    data object Dashboard : EdgeRoute
    data object Machines : EdgeRoute
    data object Ask : EdgeRoute
    data object Sync : EdgeRoute
    data object Settings : EdgeRoute

    /** Existing capture/records surface, opened from the dashboard. */
    data object Records : EdgeRoute

    /** Machine/asset detail foundation for one derived subject key. */
    data class MachineDetail(val subjectKey: String) : EdgeRoute

    /**
     * Grounded-answer citation detail (UI Phase 2): full traceability for one
     * real [com.example.EdgeMemo.core.rag.SourceReference] by its 1-based
     * index. Data resolves from the shared AskViewModel's retained result —
     * nothing is copied into navigation.
     */
    data class CitationDetail(val sourceIndex: Int) : EdgeRoute

    /**
     * Record detail (UI Phase 3): one stored knowledge record opened from an
     * asset workspace. Data resolves through the domain read path by id —
     * nothing is copied into navigation.
     */
    data class RecordDetail(val memoryId: String) : EdgeRoute

    /**
     * Conflict workspace (UI Phase 4): the unresolved-conflict list backed
     * by the Qdrant conflict store through the existing domain use cases.
     */
    data object Conflicts : EdgeRoute

    /**
     * One conflict's evidence comparison + explicit resolution workflow,
     * keyed by the DETERMINISTIC domain conflict id (the point UUID) — never
     * an array index.
     */
    data class ConflictDetail(val conflictId: String) : EdgeRoute

    /**
     * Generic record creation screen, reachable from empty Dashboard/Machines.
     * Uses the existing CreateMemoryUseCase production path.
     */
    data object CreateRecord : EdgeRoute
}

/**
 * Deterministic observable navigation state: exactly one active route, tab
 * switch collapses the push stack (standard bottom-nav behavior), back pops.
 * Owned by the shell [androidx.lifecycle.ViewModel] and shared with child
 * ViewModels so navigation intents never touch composable state directly.
 */
class EdgeNavigator {

    private val stack = ArrayDeque<EdgeRoute>().apply { addLast(EdgeRoute.Dashboard) }

    private val _route = MutableStateFlow<EdgeRoute>(stack.last())
    val route: StateFlow<EdgeRoute> = _route.asStateFlow()

    /**
     * Asset context for the Ask destination (subject namespace). Lives on the
     * navigator — the single navigation source of truth — so Machine Detail
     * can hand it to the shared Ask screen without duplicating the screen or
     * passing data through composable arguments.
     */
    private val _askAsset = MutableStateFlow<String?>(null)
    val askAsset: StateFlow<String?> = _askAsset.asStateFlow()

    val current: EdgeRoute get() = stack.last()

    val currentTab: EdgeTab?
        get() = when (current) {
            EdgeRoute.Dashboard -> EdgeTab.DASHBOARD
            EdgeRoute.Machines -> EdgeTab.MACHINES
            EdgeRoute.Ask -> EdgeTab.ASK
            EdgeRoute.Sync -> EdgeTab.SYNC
            EdgeRoute.Settings -> EdgeTab.SETTINGS
            EdgeRoute.Records -> null
            is EdgeRoute.MachineDetail -> EdgeTab.MACHINES
            is EdgeRoute.CitationDetail -> EdgeTab.ASK
            is EdgeRoute.RecordDetail -> EdgeTab.MACHINES
            EdgeRoute.Conflicts -> null
            is EdgeRoute.ConflictDetail -> null
            EdgeRoute.CreateRecord -> null
        }

    fun select(tab: EdgeTab) {
        if (tab == EdgeTab.ASK) _askAsset.value = null
        stack.clear()
        stack.addLast(
            when (tab) {
                EdgeTab.DASHBOARD -> EdgeRoute.Dashboard
                EdgeTab.MACHINES -> EdgeRoute.Machines
                EdgeTab.ASK -> EdgeRoute.Ask
                EdgeTab.SYNC -> EdgeRoute.Sync
                EdgeTab.SETTINGS -> EdgeRoute.Settings
            },
        )
        publish()
    }

    /** Open Ask grounded on an asset context (from Machine Detail). */
    fun openAsk(assetNamespace: String?) {
        _askAsset.value = assetNamespace?.trim()?.lowercase()?.ifBlank { null }
        stack.clear()
        stack.addLast(EdgeRoute.Ask)
        publish()
    }

    /** Clear the asset context without leaving the Ask tab. */
    fun clearAskAsset() {
        _askAsset.value = null
    }

    fun push(route: EdgeRoute) {
        if (current == route) return // dedupe: never stack identical routes
        stack.addLast(route)
        publish()
    }

    /** @return true when consumed; false at the root (system back takes over). */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeLast()
        publish()
        return true
    }

    fun openMachine(subjectKey: String) = push(EdgeRoute.MachineDetail(subjectKey))

    fun openRecords() = push(EdgeRoute.Records)

    fun openCitation(sourceIndex: Int) = push(EdgeRoute.CitationDetail(sourceIndex))

    fun openRecord(memoryId: String) = push(EdgeRoute.RecordDetail(memoryId))

    fun openConflicts() = push(EdgeRoute.Conflicts)

    fun openConflict(conflictId: String) = push(EdgeRoute.ConflictDetail(conflictId))

    /** Open the generic record creation screen (empty-state entry point). */
    fun openCreateRecord() = push(EdgeRoute.CreateRecord)

    private fun publish() {
        _route.value = stack.last()
    }
}
