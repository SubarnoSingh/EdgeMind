package com.example.EdgeMemo.presentation.conflicts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.presentation.components.EdgeEmptyState
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.EdgeListGroup
import com.example.EdgeMemo.presentation.components.EdgeLoadingState
import com.example.EdgeMemo.presentation.components.StatusChip
import com.example.EdgeMemo.presentation.components.relativeTimeLabel
import com.example.EdgeMemo.presentation.shell.LoadableState
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus
import com.example.EdgeMemo.ui.theme.EdgeType
import com.example.EdgeMemo.ui.theme.statusStyleFor

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

    /** The shell's Back control; conflict screens no longer draw their own. */
    const val BACK = "edge-shell-back"
    const val LOCAL_PANEL = "edge-conflict-local-panel"
    const val CLOUD_PANEL = "edge-conflict-cloud-panel"
}

/**
 * Unresolved-conflict workspace. Lists ONLY real conflicts from the durable
 * conflict store. Tapping opens the evidence comparison + resolution workflow.
 * The shell draws Back; [onBack] stays for callers.
 */
@Composable
fun ConflictListScreen(
    viewModel: ConflictListViewModel,
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
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
        Text(
            text = "Conflicts",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        when (val s = state) {
            LoadableState.Loading -> EdgeLoadingState("Reading conflicts…")
            is LoadableState.Failed -> EdgeErrorState(s.message, onRetry = viewModel::refresh)
            is LoadableState.Ready -> {
                val data = s.value
                if (data.rows.isEmpty()) {
                    EdgeEmptyState(
                        title = "No unresolved conflicts",
                        message = "When a cloud update disagrees with a record on this device, " +
                            "it shows up here for you to decide.",
                    )
                } else {
                    Text(
                        text = if (data.unresolvedCount == 1L) {
                            "1 record has two versions. Pick which one to keep."
                        } else {
                            "${data.unresolvedCount} records have two versions. Pick which one to keep for each."
                        },
                        style = EdgeType.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    val now = nowMillis()
                    EdgeListGroup(data.rows) { row ->
                        val conflict = row.conflict
                        ConflictSummaryRow(
                            conflict = conflict,
                            namespace = row.assetNamespace,
                            nowMillis = now,
                            onClick = { viewModel.openConflict(conflict.conflictId) },
                            modifier = Modifier
                                .testTag("${ConflictUiTags.ROW_PREFIX}${conflict.conflictId}")
                                .semantics {
                                    contentDescription = "Conflict on ${row.assetNamespace}: " +
                                        "device version ${conflict.localVersion ?: "unknown"}, cloud version " +
                                        "${conflict.incomingVersion ?: "unknown"}. Open to compare and resolve."
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One conflict as a list row: machine tag and age, the record's title, and
 * why it conflicts. Shared by the conflict list and the Sync screen.
 */
@Composable
internal fun ConflictSummaryRow(
    conflict: Conflict,
    namespace: String,
    nowMillis: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = EdgeLayout.cardPadding, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = namespace.uppercase(),
                style = EdgeType.numeric,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = relativeTimeLabel(conflict.detectedAt, nowMillis),
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = conflict.localTitle.ifBlank { conflict.incomingTitle.ifBlank { "Untitled record" } },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = conflictReasonText(conflict.reason),
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Real tombstone evidence flags — shown only when true.
        if (conflict.localTombstone || conflict.incomingTombstone) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                val critical = statusStyleFor(EdgeStatus.CRITICAL)
                if (conflict.localTombstone) {
                    StatusChip("Deleted on this device", color = critical.onContainer, containerColor = critical.container, showDot = false)
                }
                if (conflict.incomingTombstone) {
                    StatusChip("Deleted in the cloud", color = critical.onContainer, containerColor = critical.container, showDot = false)
                }
            }
        }
    }
}

/** The stored reason code, said plainly. Unknown codes are shown as stored. */
internal fun conflictReasonText(reason: String): String = when (reason) {
    "PULL_CONFLICT" -> "A cloud update differs from the copy on this device."
    "PUSH_CONFLICT" -> "This device's change and a cloud change collided on upload."
    else -> reason
}
