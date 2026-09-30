package com.example.EdgeMemo.presentation.conflicts

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
import androidx.compose.material3.CircularProgressIndicator
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
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.domain.conflict.ConflictResolutionAction
import com.example.EdgeMemo.domain.conflict.ConflictResolutionState
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeCardSecondary
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.EdgeLoadingState
import com.example.EdgeMemo.presentation.components.MarkdownBody
import com.example.EdgeMemo.presentation.components.PillButton
import com.example.EdgeMemo.presentation.components.StatusChip
import com.example.EdgeMemo.presentation.components.TechLabel
import com.example.EdgeMemo.presentation.components.TonalPill
import com.example.EdgeMemo.presentation.components.relativeTimeLabel
import com.example.EdgeMemo.presentation.shell.LoadableState
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus
import com.example.EdgeMemo.ui.theme.EdgeType
import com.example.EdgeMemo.ui.theme.statusColor

/**
 * Conflict evidence comparison + human-controlled resolution workflow.
 *
 * Wording mirrors ONLY the real 12B.10 resolver semantics (keep-local leaves
 * the record untouched; keep-cloud applies the incoming content at the
 * resolver's deterministic next version and may queue a follow-up sync
 * operation; dismiss keeps both sides). No AI decides anything; no state is
 * claimed that the domain did not return.
 */
@Composable
fun ConflictDetailScreen(
    viewModel: ConflictDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    val state by viewModel.uiState.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(ConflictUiTags.DETAIL)
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
                    .semantics { contentDescription = "Back to conflicts" }
                    .clickable(onClick = onBack)
                    .padding(EdgeLayout.compactGap),
            )
            Spacer(Modifier.weight(1f))
            TechLabel(text = "Conflict")
        }
        when (val s = state) {
            LoadableState.Loading -> EdgeLoadingState("Reading conflict evidence\u2026")
            is LoadableState.Failed -> EdgeErrorState(s.message, onRetry = viewModel::retry)
            is LoadableState.Ready -> ConflictDetailContent(
                data = s.value,
                viewModel = viewModel,
                nowMillis = nowMillis(),
            )
        }
    }
}

@Composable
private fun ConflictDetailContent(
    data: ConflictDetailData,
    viewModel: ConflictDetailViewModel,
    nowMillis: Long,
) {
    val conflict = data.conflict

    // ── Header: identity + authoritative status ──────────────────────────
    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap)) {
        Text(
            text = conflict.subjectKey.ifBlank { "subject \u00B7 ${conflict.conflictId.take(8)}" },
            style = EdgeType.screenTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap)) {
            StatusChip(
                text = conflict.state.name.lowercase().replace('_', ' '),
                color = when (conflict.state) {
                    ConflictResolutionState.UNRESOLVED -> EdgeStatus.WARNING.statusColor()
                    else -> EdgeStatus.HEALTHY.statusColor()
                },
                showDot = true,
            )
            Text(
                text = "detected ${relativeTimeLabel(conflict.detectedAt, nowMillis)}",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = EdgeLayout.compactGap),
            )
        }
    }

    // ── Reason / divergence summary (real fields only) ───────────────────
    EdgeCardSecondary {
        TechLabel(text = "WHY IT CONFLICTS")
        Text(
            text = "Reason: ${conflict.reason}",
            style = EdgeType.body,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "local v${conflict.localVersion ?: "?"} \u00B7 hash ${conflict.localContentHash?.take(12) ?: "\u2014"}  |  " +
                "cloud v${conflict.incomingVersion ?: "?"} \u00B7 hash ${conflict.incomingContentHash.take(12)}",
            style = EdgeType.numeric,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Both sides are different knowledge at the same point in history. " +
                "Nothing is merged automatically; the record's versions are decided " +
                "by the deterministic conflict resolver.",
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // ── Evidence panels ──────────────────────────────────────────────────
    EvidencePanel(
        title = "LOCAL RECORD",
        asset = conflict.subjectKey,
        recordId = conflict.localMemoryId,
        version = conflict.localVersion,
        origin = conflict.localOrigin,
        authority = conflict.localAuthority,
        contentHash = conflict.localContentHash,
        title1 = conflict.localTitle,
        content = conflict.localContent,
        tombstoned = conflict.localTombstone,
        status = EdgeStatus.NEUTRAL,
        modifier = Modifier.testTag(ConflictUiTags.LOCAL_PANEL),
    )
    EvidencePanel(
        title = "CLOUD RECORD",
        asset = conflict.subjectKey,
        recordId = conflict.incomingMemoryId,
        version = conflict.incomingVersion,
        origin = conflict.incomingOrigin,
        authority = conflict.incomingAuthority,
        contentHash = conflict.incomingContentHash,
        title1 = conflict.incomingTitle,
        content = conflict.incomingContent,
        tombstoned = conflict.incomingTombstone,
        status = EdgeStatus.SYNCING,
        modifier = Modifier.testTag(ConflictUiTags.CLOUD_PANEL),
    )

    // ── Resolution lifecycle ─────────────────────────────────────────────
    when (val resolution = data.resolution) {
        ResolutionUi.Idle -> {
            if (data.isUnresolved) {
                ResolutionActions(
                    onKeepLocal = { viewModel.requestResolution(ConflictResolutionAction.KEEP_LOCAL) },
                    onKeepCloud = { viewModel.requestResolution(ConflictResolutionAction.KEEP_CLOUD) },
                    onDismiss = { viewModel.requestResolution(ConflictResolutionAction.DISMISS) },
                )
            } else {
                SettledBanner(conflict, nowMillis)
            }
        }

        is ResolutionUi.Confirming -> Confirmation(
            action = resolution.action,
            conflict = conflict,
            onConfirm = viewModel::confirmResolution,
            onCancel = viewModel::cancelResolution,
        )

        ResolutionUi.Resolving -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ConflictUiTags.RESOLVING),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
        ) {
            CircularProgressIndicator(modifier = Modifier.padding(8.dp), strokeWidth = 2.dp)
            Text(
                text = "Applying your decision to the conflict store\u2026",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is ResolutionUi.Resolved -> ResolutionOutcome(resolution, nowMillis)

        is ResolutionUi.Failed -> EdgeErrorState(
            message = resolution.message,
            modifier = Modifier.testTag(ConflictUiTags.ERROR),
            onRetry = { viewModel.requestResolution(resolution.action) },
        )
    }
}

@Composable
private fun EvidencePanel(
    title: String,
    asset: String,
    recordId: String?,
    version: Int?,
    origin: String,
    authority: String?,
    contentHash: String?,
    title1: String,
    content: String,
    tombstoned: Boolean,
    status: EdgeStatus,
    modifier: Modifier = Modifier,
) {
    EdgeCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TechLabel(text = title, color = status.statusColor())
            Spacer(Modifier.weight(1f))
            // Real tombstone evidence — never rendered as merely "older".
            if (tombstoned) {
                StatusChip(
                    text = "deleted on this side",
                    color = EdgeStatus.CRITICAL.statusColor(),
                    showDot = false,
                )
            }
        }
        Text(
            text = title1.ifBlank { "(no title recorded on this side)" },
            style = EdgeType.bodyEmphasis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = buildList {
                version?.let { add("v$it") }
                add("origin ${origin.lowercase()}")
                authority?.takeIf { it.isNotBlank() }?.let { add("authority $it") }
                contentHash?.takeIf { it.isNotEmpty() }?.let { add("hash ${it.take(12)}") }
            }.joinToString(" \u00B7 "),
            style = EdgeType.numeric,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (content.isNotBlank()) {
            Spacer(Modifier.padding(vertical = 2.dp))
            MarkdownBody(content)
        } else if (!tombstoned) {
            Text(
                text = "(this side carries no text evidence)",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        recordId?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = "record ${it.take(8)}",
                style = EdgeType.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ResolutionActions(
    onKeepLocal: () -> Unit,
    onKeepCloud: () -> Unit,
    onDismiss: () -> Unit,
) {
    EdgeCardSecondary {
        TechLabel(text = "RESOLUTION \u00B7 EXPLICIT HUMAN DECISION")
        Text(
            text = "Choose what wins. Each option asks for confirmation first; nothing " +
                "changes until you confirm.",
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap)) {
            TonalPill(
                text = "Keep Local",
                onClick = onKeepLocal,
                modifier = Modifier.testTag(ConflictUiTags.KEEP_LOCAL),
            )
            TonalPill(
                text = "Keep Cloud",
                onClick = onKeepCloud,
                modifier = Modifier.testTag(ConflictUiTags.KEEP_CLOUD),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "Dismiss",
                style = EdgeType.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag(ConflictUiTags.DISMISS)
                    .semantics { contentDescription = "Dismiss this conflict, keeping both sides" }
                    .clickable(onClick = onDismiss)
                    .padding(EdgeLayout.compactGap),
            )
        }
    }
}

@Composable
private fun Confirmation(
    action: ConflictResolutionAction,
    conflict: Conflict,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val semantics = when (action) {
        ConflictResolutionAction.KEEP_LOCAL -> Triple(
            "Keep the LOCAL version",
            "The local record stays exactly as it is and this conflict closes. The cloud " +
                "version is no longer applied for this divergence. No synchronization " +
                "operation is produced.",
            "You are keeping the LOCAL version.",
        )
        ConflictResolutionAction.KEEP_CLOUD -> Triple(
            "Keep the CLOUD version",
            "The cloud content becomes the active local record at the resolver's " +
                "deterministic next version (max of the evidence versions + 1). The local " +
                "record's history and this evidence are preserved. The change is queued as " +
                "a follow-up sync operation when policy allows.",
            "You are keeping the CLOUD version.",
        )
        ConflictResolutionAction.DISMISS -> Triple(
            "Dismiss this conflict",
            "Both sides stay as they are. The conflict is marked reviewed with no change to " +
                "either record's content.",
            "Both sides will be kept as they are.",
        )
    }
    EdgeCard {
        TechLabel(text = "CONFIRM RESOLUTION", color = EdgeStatus.WARNING.statusColor())
        Text(
            text = semantics.first,
            style = EdgeType.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = semantics.third,
            style = EdgeType.body,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = semantics.second,
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap)) {
            TonalPill(
                text = "Cancel",
                onClick = onCancel,
                modifier = Modifier.testTag(ConflictUiTags.CANCEL),
            )
            Spacer(Modifier.weight(1f))
            PillButton(
                text = when (action) {
                    ConflictResolutionAction.KEEP_LOCAL -> "Confirm Keep Local"
                    ConflictResolutionAction.KEEP_CLOUD -> "Confirm Keep Cloud"
                    ConflictResolutionAction.DISMISS -> "Confirm Dismiss"
                },
                onClick = onConfirm,
                modifier = Modifier.testTag(ConflictUiTags.CONFIRM),
            )
        }
    }
}

@Composable
private fun ResolutionOutcome(resolution: ResolutionUi.Resolved, nowMillis: Long) {
    val conflict = resolution.conflict
    EdgeCard(
        modifier = Modifier.testTag(ConflictUiTags.OUTCOME),
    ) {
        TechLabel(
            text = "DOMAIN RESULT",
            color = EdgeStatus.HEALTHY.statusColor(),
        )
        Text(
            text = if (resolution.wasAlreadyResolved) {
                "This conflict had already been resolved; the durable outcome is shown " +
                    "below \u2014 nothing new was written."
            } else {
                "Resolved as ${conflict.state.name.lowercase().replace('_', ' ')} " +
                    "by the conflict resolver."
            },
            style = EdgeType.body,
            color = MaterialTheme.colorScheme.onSurface,
        )
        conflict.resolution?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Truthful sync state, read back from the real record after resolution.
        OutcomeSyncLine(resolution)
        conflict.resolvedAt?.let {
            Text(
                text = "resolved ${relativeTimeLabel(it, nowMillis)}",
                style = EdgeType.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OutcomeSyncLine(resolution: ResolutionUi.Resolved) {
    when (resolution.action) {
        ConflictResolutionAction.KEEP_CLOUD -> {
            val record = resolution.localRecord
            val status: Pair<EdgeStatus, String> = when {
                record == null -> EdgeStatus.NEUTRAL to "the resolved record is not active local memory (deleted or tombstoned)"
                record.syncState == com.example.EdgeMemo.core.model.MemorySyncState.PENDING ->
                    EdgeStatus.WARNING to "local record v${record.version} is QUEUED FOR SYNC \u2014 it reaches the cloud when a connection is available"
                record.syncState == com.example.EdgeMemo.core.model.MemorySyncState.SYNCED ->
                    EdgeStatus.SYNCED to "local record v${record.version} is already synced"
                record.syncState == com.example.EdgeMemo.core.model.MemorySyncState.FAILED ->
                    EdgeStatus.CRITICAL to "local record sync FAILED \u2014 see Sync"
                else -> EdgeStatus.NEUTRAL to "local record v${record.version} is local-only under current policy"
            }
            StatusChip(
                text = status.second,
                color = status.first.statusColor(),
                showDot = true,
            )
        }
        else -> Text(
            text = "No record content changed and no synchronization operation was produced.",
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettledBanner(conflict: Conflict, nowMillis: Long) {
    EdgeCardSecondary {
        TechLabel(
            text = "SETTLED \u00B7 ${conflict.state.name.lowercase().replace('_', ' ')}",
            color = EdgeStatus.HEALTHY.statusColor(),
        )
        Text(
            text = conflict.resolution?.takeIf { it.isNotBlank() }
                ?: "This conflict is already resolved. Nothing left to do.",
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        conflict.resolvedAt?.let {
            Text(
                text = "resolved ${relativeTimeLabel(it, nowMillis)}",
                style = EdgeType.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
