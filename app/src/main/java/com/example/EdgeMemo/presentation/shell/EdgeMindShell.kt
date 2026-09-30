package com.example.EdgeMemo.presentation.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.EdgeMemo.di.AppContainer
import com.example.EdgeMemo.presentation.ask.AskScreen
import com.example.EdgeMemo.presentation.ask.CitationDetailScreen
import com.example.EdgeMemo.presentation.components.AskIcon
import com.example.EdgeMemo.presentation.components.DashboardIcon
import com.example.EdgeMemo.presentation.components.EdgeBottomNavBar
import com.example.EdgeMemo.presentation.conflicts.ConflictDetailScreen
import com.example.EdgeMemo.presentation.conflicts.ConflictListScreen
import com.example.EdgeMemo.presentation.components.EdgeStatusBadge
import com.example.EdgeMemo.presentation.components.EdgeUiTags
import com.example.EdgeMemo.presentation.components.MachinesIcon
import com.example.EdgeMemo.presentation.components.SettingsIcon
import com.example.EdgeMemo.presentation.components.SyncIcon
import com.example.EdgeMemo.presentation.components.TechLabel
import com.example.EdgeMemo.presentation.dashboard.DashboardScreen
import com.example.EdgeMemo.presentation.dashboard.DashboardViewModel
import com.example.EdgeMemo.presentation.memory.MemoryScreen
import com.example.EdgeMemo.presentation.machines.MachineDetailScreen
import com.example.EdgeMemo.presentation.machines.RecordDetailScreen
import com.example.EdgeMemo.presentation.machines.MachinesScreen
import com.example.EdgeMemo.presentation.machines.MachinesViewModel
import com.example.EdgeMemo.presentation.record.CreateRecordScreen
import com.example.EdgeMemo.presentation.record.CreateRecordViewModel
import com.example.EdgeMemo.presentation.settings.SettingsScreen
import com.example.EdgeMemo.presentation.sync.SyncScreen
import com.example.EdgeMemo.presentation.sync.SyncViewModel
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus

/**
 * The application shell: header (identity + real connectivity/sync badges),
 * content area driven by [EdgeNavigator] state, and the bottom navigation
 * for the five industrial destinations. Existing Ask/Memory/Settings surfaces
 * are hosted unchanged — the shell only routes and decorates.
 */
@Composable
fun EdgeMindShell(
    container: AppContainer,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    profileName: String,
    onProfileNameChange: (String) -> Unit,
) {
    val shellViewModel: ShellViewModel = viewModel(factory = container.shellViewModelFactory)
    val shellState by shellViewModel.uiState.collectAsState()
    val route by shellViewModel.route.collectAsState()

    val isRootTab = route == EdgeRoute.Dashboard || route == EdgeRoute.Machines ||
        route == EdgeRoute.Ask || route == EdgeRoute.Sync || route == EdgeRoute.Settings
    BackHandler(enabled = !isRootTab) {
        shellViewModel.back()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            ShellHeader(
                shellState = shellState,
                showBack = !isRootTab,
                onBack = { shellViewModel.back() },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (val current = route) {
                    EdgeRoute.Dashboard -> DashboardTab(container, shellViewModel, shellState)
                    EdgeRoute.Machines -> MachinesTab(container, shellViewModel)
                    EdgeRoute.Ask -> AskTab(container, shellViewModel)
                    EdgeRoute.Sync -> SyncTab(container, shellViewModel, shellState)
                    EdgeRoute.Settings -> SettingsScreen(
                        darkTheme = darkTheme,
                        onToggleTheme = onToggleTheme,
                        profileName = profileName,
                        onProfileNameChange = onProfileNameChange,
                        memoryViewModel = viewModel(factory = container.memoryViewModelFactory),
                        onBack = { shellViewModel.select(EdgeTab.DASHBOARD) },
                    )
                    EdgeRoute.Records -> PushedRecordsRoute(container, shellViewModel)
                    is EdgeRoute.MachineDetail -> MachineDetailScreen(
                        viewModel = viewModel(
                            key = "machine-${current.subjectKey}",
                            factory = container.machineDetailViewModelFactory(
                                navigator = shellViewModel.navigator,
                                subjectKey = current.subjectKey,
                            ),
                        ),
                        namespace = current.subjectKey,
                        onBack = { shellViewModel.back() },
                    )
                    is EdgeRoute.CitationDetail -> CitationDetailScreen(
                        viewModel = viewModel(factory = container.askViewModelFactory),
                        sourceIndex = current.sourceIndex,
                        onBack = { shellViewModel.back() },
                    )
                    EdgeRoute.Conflicts -> ConflictListScreen(
                        viewModel = viewModel(
                            factory = container.conflictListViewModelFactory(shellViewModel.navigator),
                        ),
                        onBack = { shellViewModel.back() },
                    )
                    is EdgeRoute.ConflictDetail -> ConflictDetailScreen(
                        viewModel = viewModel(
                            key = "conflict-${current.conflictId}",
                            factory = container.conflictDetailViewModelFactory(
                                navigator = shellViewModel.navigator,
                                conflictId = current.conflictId,
                            ),
                        ),
                        onBack = { shellViewModel.back() },
                    )
                    is EdgeRoute.RecordDetail -> RecordDetailScreen(
                        viewModel = viewModel(
                            key = "record-${current.memoryId}",
                            factory = container.recordDetailViewModelFactory(
                                memoryId = current.memoryId,
                            ),
                        ),
                        onBack = { shellViewModel.back() },
                    )
                    EdgeRoute.CreateRecord -> CreateRecordScreen(
                        viewModel = viewModel(factory = container.createRecordViewModelFactory(
                            navigator = shellViewModel.navigator,
                        )),
                        onBack = { shellViewModel.back() },
                    )
                }
            }
            EdgeBottomNavBar(
                destinations = EdgeTab.entries.toList(),
                selected = shellViewModel.navigator.currentTab ?: EdgeTab.DASHBOARD,
                labelOf = { tab -> tab.label },
                iconOf = { tab ->
                    when (tab) {
                        EdgeTab.DASHBOARD -> DashboardIcon
                        EdgeTab.MACHINES -> MachinesIcon
                        EdgeTab.ASK -> AskIcon
                        EdgeTab.SYNC -> SyncIcon
                        EdgeTab.SETTINGS -> SettingsIcon
                    }
                },
                onSelect = { tab -> shellViewModel.select(tab) },
            )
        }
    }
}

private val EdgeTab.label: String
    get() = when (this) {
        EdgeTab.DASHBOARD -> "Dashboard"
        EdgeTab.MACHINES -> "Machines"
        EdgeTab.ASK -> "Ask"
        EdgeTab.SYNC -> "Sync"
        EdgeTab.SETTINGS -> "Settings"
    }

@Composable
private fun ShellHeader(shellState: ShellUiState, showBack: Boolean, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = EdgeLayout.screenPadding, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                Text(
                    text = "← Back",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .testTag("edge-shell-back")
                        .semantics { contentDescription = "Back" }
                        .clickable(onClick = onBack)
                        .padding(end = EdgeLayout.cardGap, start = 2.dp, top = 8.dp, bottom = 8.dp),
                )
            } else {
                TechLabel(text = "EdgeMind")
            }
            Spacer(Modifier.weight(1f))
            EdgeStatusBadge(
                status = if (shellState.isOnline) EdgeStatus.HEALTHY else EdgeStatus.OFFLINE,
                label = if (shellState.isOnline) "Online" else "Offline",
                modifier = Modifier.testTag(EdgeUiTags.CONNECTION_BADGE),
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
            EdgeStatusBadge(
                status = when (shellState.syncUi) {
                    ShellSyncUi.SYNCING -> EdgeStatus.SYNCING
                    ShellSyncUi.PENDING -> EdgeStatus.WARNING
                    ShellSyncUi.SYNCED -> EdgeStatus.SYNCED
                    ShellSyncUi.ATTENTION -> EdgeStatus.CRITICAL
                    ShellSyncUi.NO_OPERATIONS -> EdgeStatus.NEUTRAL
                },
                label = when (shellState.syncUi) {
                    ShellSyncUi.NO_OPERATIONS -> "no sync"
                    else -> shellState.syncUi.name.lowercase().replace('_', ' ')
                },
                modifier = Modifier.testTag(EdgeUiTags.SYNC_BADGE),
            )
        }
    }
}

@Composable
private fun DashboardTab(
    container: AppContainer,
    shellViewModel: ShellViewModel,
    shellState: ShellUiState,
) {
    val viewModel: DashboardViewModel = viewModel(
        factory = container.dashboardViewModelFactory(shellViewModel.navigator),
    )
    LaunchedEffect(Unit) { viewModel.refresh() }
    DashboardScreen(viewModel = viewModel, isOnline = shellState.isOnline)
}

/**
 * Ask destination. The navigator's asset context (set when opened from
 * Machine Detail) is projected into the shared AskViewModel, so there is one
 * Ask surface; citation taps push the deterministic detail route.
 */
@Composable
private fun AskTab(container: AppContainer, shellViewModel: ShellViewModel) {
    val viewModel: com.example.EdgeMemo.presentation.ask.AskViewModel =
        viewModel(factory = container.askViewModelFactory)
    val asset by shellViewModel.navigator.askAsset.collectAsState()
    LaunchedEffect(asset) { viewModel.setAssetContext(asset) }
    AskScreen(
        viewModel = viewModel,
        onOpenCitation = { index -> shellViewModel.navigator.openCitation(index) },
        onClearAssetContext = { shellViewModel.navigator.clearAskAsset() },
    )
}

@Composable
private fun MachinesTab(container: AppContainer, shellViewModel: ShellViewModel) {
    val viewModel: MachinesViewModel = viewModel(
        factory = container.machinesViewModelFactory(shellViewModel.navigator),
    )
    LaunchedEffect(Unit) { viewModel.refresh() }
    MachinesScreen(viewModel = viewModel)
}

@Composable
private fun SyncTab(
    container: AppContainer,
    shellViewModel: ShellViewModel,
    shellState: ShellUiState,
) {
    val viewModel: SyncViewModel = viewModel(
        factory = container.syncViewModelFactory(
            onSyncNow = container::requestQdrantSyncNow,
            navigator = shellViewModel.navigator,
        ),
    )
    LaunchedEffect(Unit) { viewModel.refresh() }
    SyncScreen(viewModel = viewModel, isOnline = shellState.isOnline)
}

@Composable
private fun PushedRecordsRoute(container: AppContainer, shellViewModel: ShellViewModel) {
    Box(Modifier.fillMaxSize()) {
        MemoryScreen(viewModel = viewModel(factory = container.memoryViewModelFactory))
    }
}
