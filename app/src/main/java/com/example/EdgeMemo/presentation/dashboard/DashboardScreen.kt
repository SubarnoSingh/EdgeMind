package com.example.EdgeMemo.presentation.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeEmptyState
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.EdgeListGroup
import com.example.EdgeMemo.presentation.components.EdgeLoadingState
import com.example.EdgeMemo.presentation.components.EdgeUiTags
import com.example.EdgeMemo.presentation.components.PillButton
import com.example.EdgeMemo.presentation.components.SectionHeader
import com.example.EdgeMemo.presentation.components.StatusDot
import com.example.EdgeMemo.presentation.components.TonalPill
import com.example.EdgeMemo.presentation.components.relativeTimeLabel
import com.example.EdgeMemo.presentation.machines.AssetModel
import com.example.EdgeMemo.presentation.shell.LoadableState
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus
import com.example.EdgeMemo.ui.theme.EdgeType
import com.example.EdgeMemo.ui.theme.statusStyleFor

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
                label = "Reading records on this device…",
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
    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        StatusReadout(data, isOnline, viewModel)
        Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
            PillButton(
                text = "Ask a question",
                onClick = viewModel::openAsk,
                modifier = Modifier
                    .weight(1f)
                    .testTag(EdgeUiTags.OPEN_ASK),
            )
            TonalPill(
                text = "Add record",
                onClick = viewModel::openCreateRecord,
                modifier = Modifier
                    .weight(1f)
                    .testTag("edge-dashboard-add-record"),
            )
        }
    }

    if (data.totalRecords == 0) {
        EdgeEmptyState(
            title = "No records yet",
            message = "Add an observation or a repair to get started. " +
                "Machines show up here once records name them.",
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        SectionHeader(
            title = "Machines",
            trailing = {
                TextAction(
                    text = "See all",
                    contentAlignment = Alignment.BottomEnd,
                    onClick = viewModel::openMachines,
                    modifier = Modifier
                        .offset(x = 10.dp)
                        .testTag("edge-dashboard-open-machines"),
                )
            },
        )
        if (data.assets.isEmpty()) {
            Text(
                text = "No machines yet. Records that name a machine tag, like P-101, group here.",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            EdgeListGroup(items = data.assets.take(MACHINE_LIMIT)) { asset ->
                MachineRow(asset = asset, onClick = { viewModel.openMachine(asset.namespace) })
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        SectionHeader(
            title = "Recent records",
            trailing = {
                TextAction(
                    text = "See all",
                    contentAlignment = Alignment.BottomEnd,
                    onClick = viewModel::openRecords,
                    modifier = Modifier
                        .offset(x = 10.dp)
                        .testTag(EdgeUiTags.OPEN_RECORDS),
                )
            },
        )
        val now = nowMillis()
        EdgeListGroup(items = data.recentRecords) { record ->
            RecordRow(
                record = record,
                nowMillis = now,
                onClick = viewModel::openRecords,
                modifier = Modifier.testTag("${EdgeUiTags.RECENT_CARD_PREFIX}${record.memoryId}"),
            )
        }
    }
}

/** Records / queued / conflicts as one gauge panel. Every figure is a real read. */
@Composable
private fun StatusReadout(data: DashboardData, isOnline: Boolean, viewModel: DashboardViewModel) {
    val sync = data.sync
    val queuedCaption: Pair<String, EdgeStatus?> = when {
        sync.failed > 0 -> "${sync.failed} failed" to EdgeStatus.CRITICAL
        sync.syncing > 0 -> "${sync.syncing} uploading" to EdgeStatus.SYNCING
        sync.pending > 0 && !isOnline -> "Uploads when online" to null
        sync.pending > 0 -> "Waiting to upload" to null
        sync.synced > 0 -> "All uploaded" to null
        else -> "Nothing queued" to null
    }
    ReadoutPanel(
        listOf(
            Readout(
                value = data.totalRecords.toString(),
                label = "Records",
                // Only worth a line when it says something the total doesn't.
                caption = if (data.maintenanceCount != data.totalRecords) {
                    "${data.maintenanceCount} maintenance"
                } else {
                    "On this device"
                },
            ),
            Readout(
                value = (sync.pending + sync.syncing + sync.failed).toString(),
                label = "To sync",
                caption = queuedCaption.first,
                captionStatus = queuedCaption.second,
                status = when {
                    sync.failed > 0 -> EdgeStatus.CRITICAL
                    sync.pending + sync.syncing > 0 -> EdgeStatus.WARNING
                    else -> null
                },
                onClick = viewModel::openSync,
                clickLabel = "Open sync status",
                tag = EdgeUiTags.DASHBOARD_SYNC_ROW,
            ),
            Readout(
                value = data.unresolvedConflicts.toString(),
                label = "Conflicts",
                caption = if (data.unresolvedConflicts > 0) "Needs review" else "None open",
                status = if (data.unresolvedConflicts > 0) EdgeStatus.WARNING else null,
                onClick = viewModel::openConflicts,
                clickLabel = "Open the conflict workspace",
                tag = EdgeUiTags.OPEN_CONFLICTS,
            ),
        ),
    )
}

@Composable
private fun MachineRow(asset: AssetModel.Asset, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = EdgeLayout.cardPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = asset.namespace.uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = plural(asset.recordCount, "record", "records"),
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (asset.pendingSyncCount > 0) {
            StatusDot(
                status = EdgeStatus.WARNING,
                label = if (asset.pendingSyncCount == asset.recordCount) {
                    "All queued"
                } else {
                    "${asset.pendingSyncCount} queued"
                },
            )
        }
    }
}

// ── Shared pieces (also used by the Sync screen) ─────────────────────────────

/** One cell of a [ReadoutPanel]. */
internal data class Readout(
    val value: String,
    val label: String,
    val caption: String? = null,
    val captionStatus: EdgeStatus? = null,
    val status: EdgeStatus? = null,
    val onClick: (() -> Unit)? = null,
    val clickLabel: String? = null,
    val tag: String? = null,
)

/** Big honest numbers side by side in one panel, split by hairlines. */
@Composable
internal fun ReadoutPanel(readouts: List<Readout>, modifier: Modifier = Modifier) {
    EdgeCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            readouts.forEachIndexed { index, readout ->
                if (index > 0) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .padding(vertical = 14.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
                ReadoutCell(readout, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ReadoutCell(readout: Readout, modifier: Modifier) {
    var cell = modifier.fillMaxHeight()
    if (readout.onClick != null) {
        cell = cell
            .semantics { readout.clickLabel?.let { contentDescription = it } }
            .clickable(onClick = readout.onClick)
    }
    readout.tag?.let { cell = cell.testTag(it) }
    Column(
        modifier = cell.padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(readout.value, style = EdgeType.metricValue, color = MaterialTheme.colorScheme.onSurface)
            if (readout.status != null) StatusDot(status = readout.status, label = "")
        }
        Text(
            text = readout.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        readout.caption?.let {
            Text(
                text = it,
                style = EdgeType.metadata,
                color = readout.captionStatus?.let { s -> statusStyleFor(s).color }
                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A record as one row: title and sync state, then type and age. */
@Composable
internal fun RecordRow(
    record: Memory,
    nowMillis: Long,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val (status, label) = syncStateDisplay(record.syncState)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = EdgeLayout.cardPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
            Text(
                text = record.title.ifBlank { "Untitled record" },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusDot(status = status, label = label)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = recordTypeLabel(record.type),
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = relativeTimeLabel(record.updatedAt, nowMillis),
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Quiet tappable text for section headers ("See all"); 48dp target. */
@Composable
internal fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    contentAlignment: Alignment = Alignment.Center,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .heightIn(min = EdgeLayout.minTarget)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        contentAlignment = contentAlignment,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

internal fun recordTypeLabel(type: MemoryType): String = when (type) {
    MemoryType.DOCUMENT -> "Document"
    MemoryType.NOTE -> "Note"
    MemoryType.OBSERVATION -> "Observation"
    MemoryType.PROCEDURE -> "Procedure"
    MemoryType.REPAIR -> "Repair"
    MemoryType.EVENT -> "Incident"
    MemoryType.CLOUD_KNOWLEDGE -> "From the cloud"
}

internal fun syncStateDisplay(state: MemorySyncState): Pair<EdgeStatus, String> = when (state) {
    MemorySyncState.PENDING -> EdgeStatus.WARNING to "Queued"
    MemorySyncState.FAILED -> EdgeStatus.CRITICAL to "Failed"
    MemorySyncState.SYNCED -> EdgeStatus.SYNCED to "Synced"
    MemorySyncState.LOCAL -> EdgeStatus.NEUTRAL to "On device"
}

internal fun plural(count: Int, one: String, many: String): String =
    "$count ${if (count == 1) one else many}"

private const val MACHINE_LIMIT = 4
