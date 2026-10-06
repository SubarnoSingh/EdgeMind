package com.example.EdgeMemo.presentation.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.core.sync.SyncSummary
import com.example.EdgeMemo.domain.conflict.ConflictResolutionState
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.EdgeListGroup
import com.example.EdgeMemo.presentation.components.EdgeLoadingState
import com.example.EdgeMemo.presentation.components.EdgeUiTags
import com.example.EdgeMemo.presentation.components.PillButton
import com.example.EdgeMemo.presentation.components.SectionHeader
import com.example.EdgeMemo.presentation.components.StatusDot
import com.example.EdgeMemo.presentation.conflicts.ConflictSummaryRow
import com.example.EdgeMemo.presentation.dashboard.Readout
import com.example.EdgeMemo.presentation.dashboard.ReadoutPanel
import com.example.EdgeMemo.presentation.dashboard.RecordRow
import com.example.EdgeMemo.presentation.dashboard.plural
import com.example.EdgeMemo.presentation.machines.AssetModel
import com.example.EdgeMemo.presentation.shell.LoadableState
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus
import com.example.EdgeMemo.ui.theme.EdgeType

/**
 * Sync destination: what is queued, what made it, what failed, and what
 * happens next — all read from the durable operation store. Conflicts open
 * the resolution workflow.
 */
@Composable
fun SyncScreen(
    viewModel: SyncViewModel,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    val state by viewModel.uiState.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("edge-sync-screen")
            .verticalScroll(rememberScrollState())
            .padding(horizontal = EdgeLayout.screenPadding, vertical = EdgeLayout.cardGap),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.sectionGap),
    ) {
        when (val s = state) {
            LoadableState.Loading -> EdgeLoadingState("Reading sync status…")
            is LoadableState.Failed -> EdgeErrorState(s.message, onRetry = viewModel::refresh)
            is LoadableState.Ready -> SyncContent(
                data = s.value,
                isOnline = isOnline,
                nowMillis = nowMillis(),
                onSyncNow = viewModel::syncNow,
                onOpenConflict = viewModel::openConflict,
            )
        }
    }
}

@Composable
private fun SyncContent(
    data: SyncData,
    isOnline: Boolean,
    nowMillis: Long,
    onSyncNow: () -> Unit,
    onOpenConflict: (String) -> Unit,
) {
    val summary = data.summary
    val readout = syncHeadline(summary, isOnline)

    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusDot(
                status = readout.status,
                label = readout.label,
                modifier = Modifier.testTag(EdgeUiTags.SYNC_BADGE),
            )
            Text(
                text = readout.headline,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = readout.next,
                style = EdgeType.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ReadoutPanel(
            listOf(
                Readout(
                    value = summary.pending.toString(),
                    label = "Queued",
                    status = if (summary.pending > 0) EdgeStatus.WARNING else null,
                ),
                Readout(
                    value = summary.synced.toString(),
                    label = "Synced",
                    status = if (summary.synced > 0) EdgeStatus.SYNCED else null,
                ),
                Readout(
                    value = summary.failed.toString(),
                    label = "Failed",
                    status = if (summary.failed > 0) EdgeStatus.CRITICAL else null,
                ),
                Readout(
                    value = summary.localOnly.toString(),
                    label = "Local only",
                ),
            ),
        )
        PillButton(
            text = "Sync now",
            onClick = onSyncNow,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("edge-sync-now"),
        )
    }

    val open = data.conflicts.filter { it.state == ConflictResolutionState.UNRESOLVED }
    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        SectionHeader(
            title = "Conflicts",
            subtitle = if (open.isEmpty()) null else "Pick which version to keep for each record.",
        )
        if (open.isEmpty()) {
            Text(
                text = "No conflicts to review.",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            EdgeListGroup(open) { conflict ->
                ConflictSummaryRow(
                    conflict = conflict,
                    namespace = conflict.subjectKey.takeIf { it.isNotBlank() }
                        ?.let { AssetModel.namespaceOf(it) } ?: "unscoped",
                    nowMillis = nowMillis,
                    onClick = { onOpenConflict(conflict.conflictId) },
                    modifier = Modifier
                        .testTag("edge-sync-conflict-${conflict.conflictId}")
                        .semantics {
                            contentDescription = "Conflict ${conflict.subjectKey}: open resolution workflow"
                        },
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        SectionHeader(title = "Recent activity")
        if (data.recentRecords.isEmpty()) {
            Text(
                text = "Nothing yet. Records you add or import show up here with their sync state.",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            EdgeListGroup(data.recentRecords) { record ->
                RecordRow(record = record, nowMillis = nowMillis)
            }
        }
    }
}

private data class SyncHeadline(
    val status: EdgeStatus,
    val label: String,
    val headline: String,
    val next: String,
)

/** One plain statement of the queue and what happens next. */
private fun syncHeadline(s: SyncSummary, isOnline: Boolean): SyncHeadline {
    fun records(n: Long) = plural(n.toInt(), "record", "records")
    fun isAre(n: Long) = if (n == 1L) "is" else "are"
    return when {
        s.syncing > 0 -> SyncHeadline(
            EdgeStatus.SYNCING,
            "Uploading",
            "Uploading ${records(s.syncing)}.",
            if (s.pending > 0) "${s.pending} more ${isAre(s.pending)} queued behind them." else "You can keep working while this runs.",
        )
        s.failed > 0 -> SyncHeadline(
            EdgeStatus.CRITICAL,
            "Upload failed",
            "${records(s.failed)} didn't upload.",
            if (isOnline) "Sync now tries them again." else "Sync now tries them again once this device is back online.",
        )
        s.pending > 0 && !isOnline -> SyncHeadline(
            EdgeStatus.OFFLINE,
            "Waiting for a connection",
            "${records(s.pending)} ${isAre(s.pending)} queued.",
            "They'll upload when this device is back online.",
        )
        s.pending > 0 -> SyncHeadline(
            EdgeStatus.WARNING,
            "Queued",
            "${records(s.pending)} ${isAre(s.pending)} queued.",
            "They upload in the background. Sync now starts it right away.",
        )
        s.synced > 0 -> SyncHeadline(
            EdgeStatus.SYNCED,
            "Up to date",
            "Everything marked for sync has uploaded.",
            "New records you mark for sync will queue here.",
        )
        else -> SyncHeadline(
            EdgeStatus.NEUTRAL,
            "Idle",
            "Nothing is queued.",
            "Records you mark for sync will queue here and upload when there's a connection.",
        )
    }
}
