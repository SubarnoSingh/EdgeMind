package com.example.EdgeMemo.presentation.conflicts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.presentation.components.EdgeCardSecondary
import com.example.EdgeMemo.presentation.components.EdgeEmptyState
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.EdgeLoadingState
import com.example.EdgeMemo.presentation.components.StatusChip
import com.example.EdgeMemo.presentation.components.TechLabel
import com.example.EdgeMemo.presentation.components.relativeTimeLabel
import com.example.EdgeMemo.presentation.shell.LoadableState
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus
import com.example.EdgeMemo.ui.theme.EdgeType
import com.example.EdgeMemo.ui.theme.statusColor

/** Test tags for UI Phase 4 conflict screens. */
object ConflictUiTags {
    const val LIST = "edge-conflict-list"
    const val ROW_PREFIX = "edge-conflict-row-"
    const val DETAIL = "edge-conflict-detail"
    const val KEEP_LOCAL = "edge-conflict-keep-local"
    const val KEEP_CLOUD = "edge-conflict-keep-cloud"
    const val DISMISS = "edge-conflict-dismiss"
    const val CONFIRM = "edge-conflict-confirm"
    const val CANCEL = "edge-conflict-cancel"
    const val RESOLVING = "edge-conflict-resolving"
    const val OUTCOME = "edge-conflict-outcome"
    const val ERROR = "edge-conflict-error"
    const val ERROR_RETRY = "edge-conflict-error-retry"
    const val BACK = "edge-conflict-back"
    const val LOCAL_PANEL = "edge-conflict-local-panel"
    const val CLOUD_PANEL = "edge-conflict-cloud-panel"
}

/**
 * Unresolved-conflict workspace. Lists ONLY real conflicts from the durable
 * conflict store, with the fields the domain actually carries. Tapping opens
 * the evidence comparison + resolution workflow.
 */
@Composable
fun ConflictListScreen(
    viewModel: ConflictListViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(ConflictUiTags.LIST)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = EdgeLayout.screenPadding, vertical = EdgeLayout.cardGap),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "\u2190 Back",
                style = EdgeType.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag(ConflictUiTags.BACK)
                    .semantics { contentDescription = "Back" }
                    .clickable(onClick = onBack)
                    .padding(EdgeLayout.compactGap),
            )
            Spacer(Modifier.weight(1f))
            TechLabel(text = "Conflict Workspace")
        }

        when (val s = state) {
            LoadableState.Loading -> EdgeLoadingState("Reading conflict evidence\u2026")
            is LoadableState.Failed -> EdgeErrorState(s.message, onRetry = viewModel::refresh)
            is LoadableState.Ready -> {
                val data = s.value
                if (data.rows.isEmpty()) {
                    EdgeEmptyState(
                        title = "No unresolved conflicts",
                        message = "Local and synchronized knowledge is consistent right now. " +
                            "New divergences appear here automatically.",
                    )
                } else {
                    TechLabel(
                        text = "UNRESOLVED CONFLICTS \u00B7 ${data.unresolvedCount}",
                        color = EdgeStatus.WARNING.statusColor(),
                    )
                    data.rows.forEach { row ->
                        ConflictRowCard(
                            row = row,
                            nowMillis = nowMillis(),
                            onOpen = { viewModel.openConflict(row.conflict.conflictId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictRowCard(
    row: ConflictRow,
    nowMillis: Long,
    onOpen: () -> Unit,
) {
    val conflict = row.conflict
    EdgeCardSecondary(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${ConflictUiTags.ROW_PREFIX}${conflict.conflictId}")
            .semantics {
                contentDescription = "Conflict on asset ${row.assetNamespace}: " +
                    "local version ${conflict.localVersion ?: "unknown"} vs cloud version " +
                    "${conflict.incomingVersion ?: "unknown"}. Open evidence and resolution."
            }
            .clickable(onClick = onOpen),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.assetNamespace.uppercase(),
                style = EdgeType.numeric,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatusChip(
                text = "unresolved",
                color = EdgeStatus.WARNING.statusColor(),
                showDot = true,
            )
            Text(
                text = " \u203A",
                style = EdgeType.bodyEmphasis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = conflict.localTitle.ifBlank { "Local record" },
            style = EdgeType.body,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap)) {
            Text(
                text = "local v${conflict.localVersion ?: "?"} \u00B7 cloud v${conflict.incomingVersion ?: "?"}",
                style = EdgeType.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "detected ${relativeTimeLabel(conflict.detectedAt, nowMillis)}",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap)) {
            Text(
                text = conflict.reason,
                style = EdgeType.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            // Real tombstone evidence flags — shown only when true.
            if (conflict.localTombstone) {
                StatusChip(
                    text = "local deleted",
                    color = EdgeStatus.CRITICAL.statusColor(),
                    showDot = false,
                )
            }
            if (conflict.incomingTombstone) {
                StatusChip(
                    text = "cloud deleted",
                    color = EdgeStatus.CRITICAL.statusColor(),
                    showDot = false,
                )
            }
        }
    }
}
