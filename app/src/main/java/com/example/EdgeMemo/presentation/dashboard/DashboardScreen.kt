package com.example.EdgeMemo.presentation.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeCardSecondary
import com.example.EdgeMemo.presentation.components.EdgeEmptyState
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.EdgeLoadingState
import com.example.EdgeMemo.presentation.components.EdgeUiTags
import com.example.EdgeMemo.presentation.components.MetricTile
import com.example.EdgeMemo.presentation.components.PillButton
import com.example.EdgeMemo.presentation.components.SectionHeader
import com.example.EdgeMemo.presentation.components.StatusChip
import com.example.EdgeMemo.presentation.components.StatusDot
import com.example.EdgeMemo.presentation.components.TechLabel
import com.example.EdgeMemo.presentation.components.TonalPill
import com.example.EdgeMemo.presentation.components.relativeTimeLabel
import com.example.EdgeMemo.presentation.shell.LoadableState
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus
import com.example.EdgeMemo.ui.theme.EdgeType

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    val state by viewModel.uiState.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(EdgeUiTags.DASHBOARD)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = EdgeLayout.screenPadding, vertical = EdgeLayout.cardGap),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.sectionGap),
    ) {
        when (val s = state) {
            LoadableState.Loading -> EdgeLoadingState(
                label = "Reading local memory…",
                modifier = Modifier.padding(top = EdgeLayout.screenPadding),
            )
            is LoadableState.Failed -> EdgeErrorState(
                message = s.message,
                onRetry = viewModel::refresh,
            )
            is LoadableState.Ready -> DashboardContent(
                data = s.value,
                isOnline = isOnline,
                viewModel = viewModel,
                nowMillis = nowMillis,
            )
        }
    }
}

@Composable
private fun DashboardContent(
    data: DashboardData,
    isOnline: Boolean,
    viewModel: DashboardViewModel,
    nowMillis: () -> Long,
) {
    OfflineNotice(visible = !isOnline)

    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        SectionHeader(
            title = "Operational overview",
            subtitle = "Local Qdrant memory — always available offline",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
            MetricTile(
                label = "Knowledge records",
                value = data.totalRecords.toString(),
                supportingLine = "active in local memory",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Assets referenced",
                value = data.assetCount.toString(),
                supportingLine = if (data.assetCount > 0) {
                    "from record subject keys"
                } else {
                    "no asset references yet"
                },
                status = if (data.assetCount > 0) EdgeStatus.HEALTHY else EdgeStatus.NEUTRAL,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
            MetricTile(
                label = "Maintenance records",
                value = data.maintenanceCount.toString(),
                supportingLine = "repairs · observations · procedures",
                modifier = Modifier.weight(1f),
            )
            // UI Phase 4: the existing real metric navigates to the conflict
            // workspace; it never adds analytics of its own.
            MetricTile(
                label = "Open conflicts",
                value = data.unresolvedConflicts.toString(),
                supportingLine = if (data.unresolvedConflicts > 0) "tap to review" else "none",
                status = when {
                    data.unresolvedConflicts > 0 -> EdgeStatus.WARNING
                    data.sync.failed > 0 -> EdgeStatus.CRITICAL
                    !isOnline -> EdgeStatus.OFFLINE
                    else -> EdgeStatus.HEALTHY
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Open the conflict workspace" }
                    .clickable(onClick = viewModel::openConflicts)
                    .testTag(EdgeUiTags.OPEN_CONFLICTS),
            )
        }
        SyncOverviewRow(data, isOnline, onOpenSync = viewModel::openSync)
    }

    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        SectionHeader(title = "Ask EdgeMind")
        EdgeCard {
            Text(
                text = "Grounded answers from your local maintenance memory — offline first.",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.padding(vertical = 4.dp))
            PillButton(
                text = "Ask about equipment",
                onClick = viewModel::openAsk,
                modifier = Modifier.testTag(EdgeUiTags.OPEN_ASK),
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        SectionHeader(
            title = "Recent activity",
            subtitle = "latest knowledge in local memory",
        )
        if (data.recentRecords.isEmpty()) {
            @Composable
            fun AddRecordAction() {
                PillButton(
                    text = "+ Add Record",
                    onClick = viewModel::openCreateRecord,
                    modifier = Modifier.testTag("edge-dashboard-add-record"),
                )
            }
            EdgeEmptyState(
                title = "Local memory is empty",
                message = "Capture notes or ingest documents from the records browser " +
                    "to start building the edge knowledge base.",
                action = ::AddRecordAction,
            )} else {
            data.recentRecords.forEach { record ->
                RecentRecordRow(
                    record = record,
                    nowMillis = nowMillis(),
                    onClick = viewModel::openRecords,
                )
            }
            TonalPill(
                text = "Browse all records",
                onClick = viewModel::openRecords,
                modifier = Modifier.testTag(EdgeUiTags.OPEN_RECORDS),
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        SectionHeader(title = "Machines & assets")
        EdgeCardSecondary {
            Text(
                text = "Asset knowledge is derived from local records grouped by subject. " +
                    "Open the machines view for the asset list foundation.",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.padding(vertical = 4.dp))
            TonalPill(
                text = "Open machines",
                onClick = viewModel::openMachines,
                modifier = Modifier
                    .align(Alignment.Start)
                    .testTag("edge-dashboard-open-machines"),
            )
        }
    }
}

/** Connectivity never blocks local data: an honest notice, never an error. */
@Composable
fun OfflineNotice(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription =
                    "Offline notice: working from local memory; cloud sync resumes when a network returns"
            }
            .padding(bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
    ) {
        TechLabel(
            text = "OFFLINE",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Local intelligence stays fully available. Sync resumes automatically.",
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SyncOverviewRow(
    data: DashboardData,
    isOnline: Boolean,
    onOpenSync: () -> Unit,
) {
    val summary = data.sync
    val (status, label) = when {
        summary.syncing > 0 -> EdgeStatus.SYNCING to "syncing"
        summary.failed > 0 -> EdgeStatus.CRITICAL to "sync needs attention"
        summary.pending > 0 -> EdgeStatus.WARNING to "sync pending"
        !isOnline -> EdgeStatus.OFFLINE to "offline · queued locally"
        summary.synced > 0 -> EdgeStatus.SYNCED to "synced"
        else -> EdgeStatus.NEUTRAL to "no sync activity"
    }
    EdgeCardSecondary(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
        ) {
            StatusDot(
                status = status,
                label = label,
                modifier = Modifier.testTag(EdgeUiTags.DASHBOARD_SYNC_ROW),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            StatusChip(
                text = "${summary.pending} pending · ${summary.synced} synced · ${summary.failed} failed",
                showDot = false,
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(vertical = 2.dp))
        Text(
            text = "Sync status reflects durable Qdrant operation state only — " +
                "queued work never shows as synchronized.",
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentRecordRow(
    record: Memory,
    nowMillis: Long,
    onClick: () -> Unit,
) {
    val syncStatus = when (record.syncState) {
        MemorySyncState.PENDING -> EdgeStatus.WARNING
        MemorySyncState.FAILED -> EdgeStatus.CRITICAL
        MemorySyncState.SYNCED -> EdgeStatus.SYNCED
        MemorySyncState.LOCAL -> EdgeStatus.NEUTRAL
    }
    EdgeCardSecondary(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${EdgeUiTags.RECENT_CARD_PREFIX}${record.memoryId}")
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
        ) {
            Text(
                text = record.title.ifBlank { record.memoryId.take(8) },
                style = EdgeType.bodyEmphasis,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusDot(status = syncStatus, label = record.syncState.name.lowercase())
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
        ) {
            TechLabel(
                text = record.type.name,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "· ${record.source}",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Text(
                text = relativeTimeLabel(record.updatedAt, nowMillis),
                style = EdgeType.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
