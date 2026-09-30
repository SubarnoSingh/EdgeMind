package com.example.EdgeMemo.presentation.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeCardSecondary
import com.example.EdgeMemo.presentation.components.EdgeEmptyState
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.EdgeLoadingState
import com.example.EdgeMemo.presentation.components.EdgeUiTags
import com.example.EdgeMemo.presentation.components.StatusChip
import com.example.EdgeMemo.presentation.components.StatusDot
import com.example.EdgeMemo.presentation.components.TechLabel
import com.example.EdgeMemo.presentation.components.TonalPill
import com.example.EdgeMemo.presentation.components.relativeTimeLabel
import com.example.EdgeMemo.presentation.shell.LoadableState
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus
import com.example.EdgeMemo.ui.theme.EdgeType

/**
 * Sync / Activity destination. Shows the durable synchronization picture
 * (Qdrant operation-store state, conflicts, recent local activity) plus
 * navigation into the Phase 4 conflict resolution workflow. The full activity
 * event timeline remains deferred to a later phase and is stated honestly.
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
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TechLabel(text = "Synchronization")
            Spacer(Modifier.weight(1f))
            TonalPill(
                text = "Sync now",
                onClick = viewModel::syncNow,
                modifier = Modifier.testTag("edge-sync-now"),
            )
        }
        when (val s = state) {
            LoadableState.Loading -> EdgeLoadingState("Reading sync state…")
            is LoadableState.Failed -> EdgeErrorState(s.message, onRetry = viewModel::refresh)
            is LoadableState.Ready -> SyncContent(s.value, isOnline, nowMillis(), onOpenConflict = viewModel::openConflict)
        }
    }
}

@Composable
private fun SyncContent(
    data: SyncData,
    isOnline: Boolean,
    nowMillis: Long,
    onOpenConflict: (String) -> Unit,
) {
    val summary = data.summary
    val status = when {
        summary.syncing > 0 -> EdgeStatus.SYNCING to "syncing"
        summary.failed > 0 -> EdgeStatus.CRITICAL to "needs attention"
        summary.pending > 0 && !isOnline -> EdgeStatus.OFFLINE to "queued · offline"
        summary.pending > 0 -> EdgeStatus.WARNING to "pending"
        summary.synced > 0 -> EdgeStatus.SYNCED to "synced"
        else -> EdgeStatus.NEUTRAL to "idle"
    }
    EdgeCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
        ) {
            StatusDot(
                status = status.first,
                label = status.second,
                modifier = Modifier.testTag(EdgeUiTags.SYNC_BADGE),
            )
            Spacer(Modifier.weight(1f))
            StatusChip(
                text = if (isOnline) "ONLINE" else "OFFLINE",
                color = if (isOnline) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    statusStyleColorOffline()
                },
                showDot = false,
            )
        }
        Spacer(Modifier.padding(vertical = 4.dp))
        Text(
            text = "Pending ${summary.pending} · in flight ${summary.syncing} · " +
                "synced ${summary.synced} · failed ${summary.failed} · " +
                "local-only ${summary.localOnly}",
            style = EdgeType.numeric,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Counts read directly from durable operation state. " +
                "Nothing here is simulated; queued work is never labeled synced.",
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap)) {
        TechLabel(text = "Conflicts")
        if (data.conflicts.isEmpty()) {
            EdgeCardSecondary {
                Text(
                    text = "No unresolved conflicts in local memory.",
                    style = EdgeType.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            data.conflicts.forEach { conflict ->
                EdgeCardSecondary(
                    modifier = Modifier
                        .testTag("edge-sync-conflict-${conflict.conflictId}")
                        .semantics {
                            contentDescription = "Conflict ${conflict.subjectKey}: open resolution workflow"
                        }
                        .clickable { onOpenConflict(conflict.conflictId) },
                ) {
                    Text(
                        text = conflict.subjectKey.ifBlank { conflict.conflictId.take(8) },
                        style = EdgeType.bodyEmphasis,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "local v${conflict.localVersion ?: "?"} vs cloud " +
                            "v${conflict.incomingVersion ?: "?"} · ${conflict.reason}",
                        style = EdgeType.metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap)) {
        TechLabel(text = "Recent local activity")
        if (data.recentRecords.isEmpty()) {
            EdgeEmptyState(
                title = "No local activity yet",
                message = "Captured notes and ingested documents appear here.",
            )
        } else {
            data.recentRecords.forEach { record ->
                EdgeCardSecondary {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = record.title.ifBlank { record.memoryId.take(8) },
                            style = EdgeType.body,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = relativeTimeLabel(record.updatedAt, nowMillis),
                            style = EdgeType.numeric,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = record.type.name.lowercase() + " · " + record.source,
                        style = EdgeType.metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun statusStyleColorOffline(): androidx.compose.ui.graphics.Color =
    com.example.EdgeMemo.ui.theme.statusStyleFor(EdgeStatus.OFFLINE).color
