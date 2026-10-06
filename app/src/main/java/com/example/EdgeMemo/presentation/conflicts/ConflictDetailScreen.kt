package com.example.EdgeMemo.presentation.conflicts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.domain.conflict.ConflictResolutionAction
import com.example.EdgeMemo.domain.conflict.ConflictResolutionState
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeCardSecondary
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.EdgeLoadingState
import com.example.EdgeMemo.presentation.components.MarkdownBody
import com.example.EdgeMemo.presentation.components.PillButton
import com.example.EdgeMemo.presentation.components.SectionHeader
import com.example.EdgeMemo.presentation.components.StatusChip
import com.example.EdgeMemo.presentation.components.StatusDot
import com.example.EdgeMemo.presentation.components.TonalPill
import com.example.EdgeMemo.presentation.components.relativeTimeLabel
import com.example.EdgeMemo.presentation.dashboard.TextAction
import com.example.EdgeMemo.presentation.machines.AssetModel
import com.example.EdgeMemo.presentation.shell.LoadableState
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus
import com.example.EdgeMemo.ui.theme.EdgeType
import com.example.EdgeMemo.ui.theme.statusStyleFor

/**
 * Conflict evidence comparison + human-controlled resolution workflow.
 *
 * Wording mirrors ONLY the real resolver semantics (keep-local leaves the
 * record untouched; keep-cloud applies the incoming content at the resolver's
 * next version and may queue a follow-up sync operation; dismiss keeps both
 * sides). No AI decides anything; no state is claimed that the domain did
 * not return. The shell draws Back; [onBack] stays for callers.
 */
@Composable
fun ConflictDetailScreen(
    viewModel: ConflictDetailViewModel,
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.sectionGap),
    ) {
        when (val s = state) {
            LoadableState.Loading -> EdgeLoadingState("Reading conflict…")
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

    // ── Subject: the machine tag, state and why ──────────────────────────
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val tag = conflict.subjectKey.takeIf { it.isNotBlank() }?.let { AssetModel.namespaceOf(it) }
        Text(
            text = tag?.uppercase() ?: "Conflict",
            style = EdgeType.nameplate,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val status = if (data.isUnresolved) EdgeStatus.WARNING else EdgeStatus.HEALTHY
            StatusDot(status = status, label = stateLabel(conflict.state))
            Text(
                text = "Detected ${relativeTimeLabel(conflict.detectedAt, nowMillis)}",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = conflictReasonText(conflict.reason) +
                if (data.isUnresolved) " Nothing is merged until you choose." else "",
            style = EdgeType.body,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    // ── Evidence: the two versions, same layout so they compare line by line ──
    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        SectionHeader(title = "Compare versions")
        EvidencePanel(
            side = "On this device",
            sideStatus = EdgeStatus.NEUTRAL,
            recordId = conflict.localMemoryId,
            version = conflict.localVersion,
            origin = conflict.localOrigin,
            authority = conflict.localAuthority,
            contentHash = conflict.localContentHash,
            title = conflict.localTitle,
            content = conflict.localContent,
            tombstoned = conflict.localTombstone,
            modifier = Modifier.testTag(ConflictUiTags.LOCAL_PANEL),
        )
        EvidencePanel(
            side = "From the cloud",
            sideStatus = EdgeStatus.SYNCING,
            recordId = conflict.incomingMemoryId,
            version = conflict.incomingVersion,
            origin = conflict.incomingOrigin,
            authority = conflict.incomingAuthority,
            contentHash = conflict.incomingContentHash,
            title = conflict.incomingTitle,
            content = conflict.incomingContent,
            tombstoned = conflict.incomingTombstone,
            modifier = Modifier.testTag(ConflictUiTags.CLOUD_PANEL),
        )
    }

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
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                text = "Saving your decision…",
                style = EdgeType.body,
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
    side: String,
    sideStatus: EdgeStatus,
    recordId: String?,
    version: Int?,
    origin: String,
    authority: String?,
    contentHash: String?,
    title: String,
    content: String,
    tombstoned: Boolean,
    modifier: Modifier = Modifier,
) {
    EdgeCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(status = sideStatus, label = side, modifier = Modifier.weight(1f))
            Text(
                text = version?.let { "Version $it" } ?: "Version unknown",
                style = EdgeType.numeric,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(12.dp))
        // Real tombstone evidence — never rendered as merely "older".
        if (tombstoned) {
            val critical = statusStyleFor(EdgeStatus.CRITICAL)
            StatusChip(
                text = "Deleted on this side",
                color = critical.onContainer,
                containerColor = critical.container,
                showDot = false,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = title.ifBlank { "No title on this side" },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        if (content.isNotBlank()) {
            MarkdownBody(content)
        } else if (!tombstoned) {
            Text(
                text = "No text on this side.",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Fact("Source", origin.lowercase().replaceFirstChar { it.uppercase() })
            authority?.takeIf { it.isNotBlank() }?.let { Fact("Authority", it) }
            recordId?.takeIf { it.isNotBlank() }?.let { Fact("Record", it.take(8), raw = true) }
            contentHash?.takeIf { it.isNotEmpty() }?.let { Fact("Content hash", it.take(12), raw = true) }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, raw: Boolean = false) {
    Row {
        Text(
            text = label,
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp),
        )
        Text(
            text = value,
            style = if (raw) EdgeType.code else EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ResolutionActions(
    onKeepLocal: () -> Unit,
    onKeepCloud: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        SectionHeader(
            title = "Which version should stay?",
            subtitle = "You'll confirm before anything changes.",
        )
        TonalPill(
            text = "Keep this device's version",
            onClick = onKeepLocal,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ConflictUiTags.KEEP_LOCAL),
        )
        TonalPill(
            text = "Use the cloud version",
            onClick = onKeepCloud,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ConflictUiTags.KEEP_CLOUD),
        )
        TextAction(
            text = "Dismiss and keep both as they are",
            onClick = onDismiss,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ConflictUiTags.DISMISS)
                .semantics { contentDescription = "Dismiss this conflict, keeping both sides" },
        )
    }
}

@Composable
private fun Confirmation(
    action: ConflictResolutionAction,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val (question, effect, confirmLabel) = when (action) {
        ConflictResolutionAction.KEEP_LOCAL -> Triple(
            "Keep this device's version?",
            "The record on this device stays exactly as it is and the cloud version is set aside. " +
                "Nothing new is queued to sync.",
            "Keep device version",
        )
        ConflictResolutionAction.KEEP_CLOUD -> Triple(
            "Use the cloud version?",
            "The cloud text becomes the current record on this device, saved as a new version. " +
                "This device's text stays in the record's history. The change is queued to sync " +
                "if the record's sync setting allows it.",
            "Use cloud version",
        )
        ConflictResolutionAction.DISMISS -> Triple(
            "Dismiss this conflict?",
            "Both versions stay as they are and the conflict is marked as reviewed.",
            "Dismiss conflict",
        )
    }
    EdgeCard {
        Text(question, style = EdgeType.sectionTitle, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(effect, style = EdgeType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
            TonalPill(
                text = "Cancel",
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .testTag(ConflictUiTags.CANCEL),
            )
            PillButton(
                text = confirmLabel,
                onClick = onConfirm,
                modifier = Modifier
                    .weight(1.4f)
                    .testTag(ConflictUiTags.CONFIRM),
            )
        }
    }
}

@Composable
private fun ResolutionOutcome(resolution: ResolutionUi.Resolved, nowMillis: Long) {
    val conflict = resolution.conflict
    EdgeCard(modifier = Modifier.testTag(ConflictUiTags.OUTCOME)) {
        Text(
            text = if (resolution.wasAlreadyResolved) {
                "This conflict was already resolved, so nothing new was saved."
            } else {
                outcomeSentence(conflict.state)
            },
            style = EdgeType.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        // Truthful sync state, read back from the real record after resolution.
        OutcomeSyncLine(resolution)
        conflict.resolvedAt?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Resolved ${relativeTimeLabel(it, nowMillis)}",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OutcomeSyncLine(resolution: ResolutionUi.Resolved) {
    if (resolution.action != ConflictResolutionAction.KEEP_CLOUD) {
        Text(
            text = "No record content changed and nothing was queued to sync.",
            style = EdgeType.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val record = resolution.localRecord
    val (status, text) = when {
        record == null -> EdgeStatus.NEUTRAL to
            "The resolved record isn't in active memory any more (it was deleted)."
        record.syncState == MemorySyncState.PENDING -> EdgeStatus.WARNING to
            "Version ${record.version} is queued to sync. It uploads when there's a connection."
        record.syncState == MemorySyncState.SYNCED -> EdgeStatus.SYNCED to
            "Version ${record.version} is already synced."
        record.syncState == MemorySyncState.FAILED -> EdgeStatus.CRITICAL to
            "Version ${record.version} failed to sync. Check the Sync tab."
        else -> EdgeStatus.NEUTRAL to
            "Version ${record.version} stays on this device only, per its sync setting."
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(status = status, label = "", modifier = Modifier.padding(top = 8.dp))
        Text(text, style = EdgeType.body, color = statusStyleFor(status).onContainer)
    }
}

@Composable
private fun SettledBanner(conflict: Conflict, nowMillis: Long) {
    EdgeCardSecondary {
        Text(
            text = outcomeSentence(conflict.state),
            style = EdgeType.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        conflict.resolvedAt?.let {
            Text(
                text = "Resolved ${relativeTimeLabel(it, nowMillis)}",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun stateLabel(state: ConflictResolutionState): String = when (state) {
    ConflictResolutionState.UNRESOLVED -> "Unresolved"
    ConflictResolutionState.RESOLVED_LOCAL -> "Resolved"
    ConflictResolutionState.RESOLVED_CLOUD -> "Resolved"
    ConflictResolutionState.RESOLVED_MERGED -> "Resolved"
    ConflictResolutionState.DISMISSED -> "Dismissed"
}

/** What the domain says happened, from the conflict's stored state. */
private fun outcomeSentence(state: ConflictResolutionState): String = when (state) {
    ConflictResolutionState.RESOLVED_LOCAL -> "Kept this device's version."
    ConflictResolutionState.RESOLVED_CLOUD -> "Now using the cloud version."
    ConflictResolutionState.RESOLVED_MERGED -> "Resolved with a merged version."
    ConflictResolutionState.DISMISSED -> "Dismissed. Both versions were kept."
    ConflictResolutionState.UNRESOLVED -> "Still unresolved."
}
