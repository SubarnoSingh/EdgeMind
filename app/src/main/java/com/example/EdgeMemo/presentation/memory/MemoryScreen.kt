package com.example.EdgeMemo.presentation.memory

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.policy.PolicyDecision
import com.example.EdgeMemo.core.sync.SyncSummary
import com.example.EdgeMemo.domain.cloud.CloudPullResult
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.domain.conflict.ConflictResolutionAction
import com.example.EdgeMemo.domain.document.DocumentIngestionService
import com.example.EdgeMemo.domain.document.IngestionStage
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeCardSecondary
import com.example.EdgeMemo.presentation.components.EdgeDimens
import com.example.EdgeMemo.presentation.components.PillButton
import com.example.EdgeMemo.presentation.components.SectionHeader
import com.example.EdgeMemo.presentation.components.StatusChip
import com.example.EdgeMemo.presentation.components.TechLabel
import com.example.EdgeMemo.presentation.components.TonalPill
import com.example.EdgeMemo.ui.theme.LocalEdgeColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MemoryScreen(viewModel: MemoryViewModel) {
    val state by viewModel.uiState.collectAsState()
    var captureOpen by rememberSaveable { mutableStateOf(false) }
    val expandedMemories = remember { mutableStateMapOf<String, Boolean>() }

    // The ViewModel is Activity-scoped and shared with Settings; it only
    // reloads from its own actions. Mutations from other paths (explicit
    // "Save to memory" on the Ask screen, cloud answers cached, conflict
    // resolution) write through the repository without notifying it, so the
    // list refreshes every time this screen becomes visible (tab switch /
    // returning from Settings). No polling, no recreation, no extra writes.
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = viewModel::onDocumentPicked,
    )
    val launchImport = {
        importLauncher.launch(
            arrayOf("application/pdf", "text/plain", "text/markdown"),
        )
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = EdgeDimens.spacingL),
            verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
        ) {
            HeaderRow(state)
            SearchBar(
                query = state.query,
                onQueryChange = viewModel::onQueryChange,
                onSearch = viewModel::search,
                onClear = viewModel::clearSearch,
                searchActive = state.searchActive,
            )
            ActionPills(
                captureOpen = captureOpen,
                onToggleCapture = { captureOpen = !captureOpen },
                onImport = launchImport,
                importEnabled = !state.ingestion.isActive,
                unresolvedConflicts = state.unresolvedConflictCount,
                conflictsVisible = state.conflictsVisible,
                onToggleConflicts = viewModel::toggleConflicts,
                onPullCloud = viewModel::pullCloud,
                pullBusy = state.isBusy,
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding(),
            contentPadding = PaddingValues(
                start = EdgeDimens.spacingL,
                end = EdgeDimens.spacingL,
                top = EdgeDimens.spacingS,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
        ) {
            state.error?.let { error ->
                item {
                    EdgeCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "ERROR",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = "${error::class.simpleName}: ${error.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            TextButton(onClick = viewModel::consumeError) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }

            if (captureOpen) {
                item {
                    CaptureSection(
                        title = state.draftTitle,
                        content = state.draftContent,
                        type = state.draftType,
                        userChoice = state.draftUserChoice,
                        policy = state.draftPolicy,
                        onTitleChange = viewModel::onTitleChange,
                        onContentChange = viewModel::onContentChange,
                        onTypeChange = viewModel::onTypeChange,
                        onUserSyncChoiceChange = viewModel::onUserSyncChoiceChange,
                        onSave = viewModel::create,
                    )
                }
            }

            if (state.ingestion.stage != IngestionStage.IDLE || state.ingestion.error != null) {
                item { IngestionStatus(state.ingestion, viewModel::dismissIngestion) }
            }

            if (state.pullStatus !is CloudPullStatus.Idle) {
                item { PullStatusCard(state.pullStatus) }
            }

            if (state.conflictsVisible && (state.conflicts.isNotEmpty() || state.unresolvedConflictCount > 0)) {
                item {
                    ConflictsCard(
                        conflicts = state.conflicts,
                        onResolve = viewModel::resolve,
                    )
                }
            }

            item { SyncStatusCard(state.syncSummary) }

            item {
                ListHeader(
                    searchActive = state.searchActive,
                    itemCount = state.items.size,
                    memoryCount = state.memoryCount,
                )
            }

            if (state.items.isEmpty()) {
                item { EmptyState(searchActive = state.searchActive) { captureOpen = true } }
            }

            items(state.items.size, key = { state.items[it].memoryId }) { index ->
                val memory = state.items[index]
                val score = state.results.firstOrNull { it.memory.memoryId == memory.memoryId }?.score
                MemoryCard(
                    memory = memory,
                    score = score,
                    expanded = expandedMemories[memory.memoryId] == true,
                    onToggle = {
                        expandedMemories[memory.memoryId] = expandedMemories[memory.memoryId] != true
                    },
                    onDelete = { viewModel.delete(memory.memoryId) },
                )
            }
        }
    }
}

@Composable
private fun HeaderRow(state: MemoryUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = EdgeDimens.spacingS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = "Memory",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${state.memoryCount} memories \u00b7 on this device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.isBusy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    searchActive: Boolean,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = {
            Text(
                "Search memory\u2026",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        },
        leadingIcon = {
            androidx.compose.material3.Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (searchActive) {
                androidx.compose.material3.IconButton(onClick = onClear) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        shape = RoundedCornerShape(EdgeDimens.inputRadius),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun ActionPills(
    captureOpen: Boolean,
    onToggleCapture: () -> Unit,
    onImport: () -> Unit,
    importEnabled: Boolean,
    unresolvedConflicts: Long,
    conflictsVisible: Boolean,
    onToggleConflicts: () -> Unit,
    onPullCloud: () -> Unit,
    pullBusy: Boolean,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
        ) {
            TonalPill(
                text = if (captureOpen) "Close capture" else "Capture",
                onClick = onToggleCapture,
                icon = if (captureOpen) Icons.Filled.Clear else Icons.Filled.Add,
            )
            if (importEnabled) {
                TonalPill(
                    text = "Import document",
                    onClick = onImport,
                )
            } else {
                TonalPill(
                    text = "Importing\u2026",
                    onClick = { },
                    enabled = false,
                )
            }
            TonalPill(
                text = "Pull cloud",
                onClick = onPullCloud,
                icon = Icons.Filled.Refresh,
                enabled = !pullBusy,
            )
            if (unresolvedConflicts > 0 || conflictsVisible) {
                TonalPill(
                    text = if (conflictsVisible) "Hide conflicts" else "Conflicts ($unresolvedConflicts)",
                    onClick = onToggleConflicts,
                )
            }
        }
        // Trailing scrim: signals the row scrolls when more actions exist.
        // Non-clickable, so taps pass through to the pills underneath.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(36.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(
                            androidx.compose.ui.graphics.Color.Transparent,
                            MaterialTheme.colorScheme.background.copy(alpha = 0.9f),
                        ),
                    ),
                ),
        )
    }
}

// ── Capture ────────────────────────────────────────────────────────────────

@Composable
private fun CaptureSection(
    title: String,
    content: String,
    type: MemoryType,
    userChoice: SyncDecision?,
    policy: PolicyDecision?,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onTypeChange: (MemoryType) -> Unit,
    onUserSyncChoiceChange: (SyncDecision?) -> Unit,
    onSave: () -> Unit,
) {
    EdgeCard {
        Column(verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingM)) {
            SectionHeader(title = "New memory", subtitle = "Stored on device. Policy decides what may leave.")
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                label = { Text("Title") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                label = { Text("Content") },
                minLines = 3,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
            ) {
                val selectable = listOf(
                    MemoryType.NOTE,
                    MemoryType.OBSERVATION,
                    MemoryType.PROCEDURE,
                    MemoryType.REPAIR,
                    MemoryType.EVENT,
                )
                selectable.forEach { candidate ->
                    FilterChip(
                        selected = type == candidate,
                        onClick = { onTypeChange(candidate) },
                        label = { Text(candidate.name.lowercase()) },
                    )
                }
            }
            Text("Sync policy", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
            ) {
                FilterChip(
                    selected = userChoice == null,
                    onClick = { onUserSyncChoiceChange(null) },
                    label = { Text("auto") },
                )
                SyncDecision.entries.forEach { candidate ->
                    FilterChip(
                        selected = userChoice == candidate,
                        onClick = { onUserSyncChoiceChange(candidate) },
                        label = { Text(candidate.name.lowercase().replace("_", " ")) },
                    )
                }
            }
            policy?.let { decision ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
                    TechLabel(
                        text = "policy \u00b7 ${decision.syncDecision.name.lowercase().replace("_", " ")}",
                        color = policyColor(decision.syncDecision),
                    )
                    StatusChip(
                        text = policyEligibility(decision.syncDecision),
                        color = policyColor(decision.syncDecision),
                        containerColor = policyContainer(decision.syncDecision),
                        showDot = false,
                    )
                }
                Text(
                    text = decision.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PillButton(
                text = "Save to local memory",
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                enabled = title.isNotBlank() || content.isNotBlank(),
            )
        }
    }
}

// ── Import / ingestion ─────────────────────────────────────────────────────

@Composable
private fun IngestionStatus(
    state: DocumentIngestionState,
    onDismiss: () -> Unit,
) {
    if (state.stage == IngestionStage.IDLE && state.error == null) return
    val edgeColors = LocalEdgeColors.current
    EdgeCard {
        Column(verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
                if (state.isActive) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Text(
                    text = buildString {
                        append(state.sourceName ?: "document")
                        append(" \u00b7 ")
                        append(stageLabel(state.stage))
                        if (state.stage == IngestionStage.COMPLETED) {
                            append(" \u00b7 ${state.chunkCount} chunks")
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.stage == IngestionStage.COMPLETED) {
                        edgeColors.positive
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            state.error?.let { error ->
                Text(
                    text = "${error::class.simpleName}: ${error.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

private fun stageLabel(stage: IngestionStage): String = when (stage) {
    IngestionStage.IDLE -> "idle"
    IngestionStage.SELECTING -> "opening"
    IngestionStage.EXTRACTING -> "extracting text"
    IngestionStage.CHUNKING -> "chunking"
    IngestionStage.EMBEDDING -> "embedding"
    IngestionStage.STORING -> "storing locally"
    IngestionStage.COMPLETED -> "stored locally"
    IngestionStage.FAILED -> "failed"
}

// ── Cloud pull / conflicts ─────────────────────────────────────────────────

@Composable
private fun PullStatusCard(status: CloudPullStatus) {
    val edgeColors = LocalEdgeColors.current
    EdgeCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Cloud knowledge", style = MaterialTheme.typography.titleSmall)
            when (status) {
                CloudPullStatus.Idle -> Unit
                CloudPullStatus.Unavailable -> Text(
                    "no cloud backend is configured \u2014 nothing was received or fabricated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                is CloudPullStatus.Success -> {
                    Text(
                        text = pullResultText(status.result),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StatusChip(
                        text = "pulled from cloud",
                        color = edgeColors.positive,
                        containerColor = edgeColors.positiveContainer,
                    )
                }
                is CloudPullStatus.Failed -> Text(
                    status.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun pullResultText(result: CloudPullResult): String = buildString {
    append("received: ")
    append(result.applied)
    append(" new/updated \u00b7 ")
    append(result.conflicts)
    append(" conflicts \u00b7 ")
    append(result.tombstoned)
    append(" removed")
    if (result.duplicates > 0) {
        append(" \u00b7 ")
        append(result.duplicates)
        append(" duplicates skipped")
    }
    result.cursor?.let { append(" \u00b7 next \u00b7 $it") }
}

@Composable
private fun ConflictsCard(
    conflicts: List<Conflict>,
    onResolve: (String, ConflictResolutionAction) -> Unit,
) {
    EdgeCard {
        Column(verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
            SectionHeader(
                title = "Conflicts",
                subtitle = "Contradictory knowledge is kept visible until you decide.",
            )
            conflicts.forEach { conflict ->
                ConflictItem(conflict, onResolve)
            }
        }
    }
}

@Composable
private fun ConflictItem(
    conflict: Conflict,
    onResolve: (String, ConflictResolutionAction) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            Modifier.padding(EdgeDimens.spacingM),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "conflict \u00b7 ${conflict.subjectKey.ifBlank { "no subject" }}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = conflict.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ConflictSide("LOCAL", conflict.localOrigin, conflict.localVersion, conflict.localAuthority, conflict.localTitle, conflict.localContent)
            ConflictSide("CLOUD", conflict.incomingOrigin, conflict.incomingVersion, conflict.incomingAuthority, conflict.incomingTitle, conflict.incomingContent)
            if (conflict.state.name != "UNRESOLVED") {
                Text(
                    text = "${conflict.state.name.lowercase()} \u00b7 ${conflict.resolution ?: ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
                    TextButton(onClick = { onResolve(conflict.conflictId, ConflictResolutionAction.KEEP_LOCAL) }) {
                        Text("Keep local")
                    }
                    TextButton(onClick = { onResolve(conflict.conflictId, ConflictResolutionAction.KEEP_CLOUD) }) {
                        Text("Accept cloud")
                    }
                    TextButton(onClick = { onResolve(conflict.conflictId, ConflictResolutionAction.DISMISS) }) {
                        Text("Dismiss")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictSide(
    label: String,
    origin: String,
    version: Int?,
    authority: String?,
    title: String,
    content: String,
) {
    Text(
        text = "$label \u00b7 ${origin.lowercase()} \u00b7 v${version ?: "?"} \u00b7 authority ${authority ?: "\u2014"}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = "$title \u2014 ${content.take(80)}",
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

// ── Sync summary ───────────────────────────────────────────────────────────

@Composable
private fun SyncStatusCard(summary: SyncSummary) {
    val edgeColors = LocalEdgeColors.current
    val total = summary.total
    EdgeCardSecondary {
        Column(verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
            Text(
                text = "Synchronization",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
            ) {
                if (summary.localOnly > 0) {
                    StatusChip(
                        text = "local only ${summary.localOnly}",
                        color = edgeColors.accentAmber,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f),
                    )
                }
                if (summary.pending > 0) {
                    StatusChip(
                        text = "pending ${summary.pending}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (summary.syncing > 0) {
                    StatusChip(
                        text = "syncing ${summary.syncing}",
                        color = edgeColors.accentBlue,
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                    )
                }
                if (summary.synced > 0) {
                    StatusChip(
                        text = "synced ${summary.synced}",
                        color = edgeColors.positive,
                        containerColor = edgeColors.positiveContainer.copy(alpha = 0.75f),
                    )
                }
                if (summary.failed > 0) {
                    StatusChip(
                        text = "failed ${summary.failed}",
                        color = MaterialTheme.colorScheme.error,
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f),
                    )
                }
                if (total == 0L && summary.localOnly == 0L) {
                    StatusChip(
                        text = "nothing queued",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = when {
                    summary.failed > 0 -> "failed operations will retry automatically when the network allows."
                    total > 0 && summary.synced == total -> "all outbound operations acknowledged by the cloud."
                    total == 0L && summary.localOnly > 0L -> "nothing leaves this device without policy approval."
                    else -> "outbound operations wait for connectivity and policy approval."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── List ───────────────────────────────────────────────────────────────────

@Composable
private fun ListHeader(searchActive: Boolean, itemCount: Int, memoryCount: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = EdgeDimens.spacingS),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TechLabel(
            text = when {
                searchActive -> "${if (itemCount == 1) "result" else "results"} · $itemCount"
                else -> "all memories"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (searchActive) {
            Text(
                text = "semantic search",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun EmptyState(searchActive: Boolean, onOpenCapture: () -> Unit) {
    EdgeCardSecondary {
        Column(
            verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (searchActive) "No matching memories" else "No memories yet",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (searchActive) {
                    "Try different wording, or clear the search to browse everything."
                } else {
                    "Capture a note or import a document to start building your offline memory."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!searchActive) {
                PillButton(text = "Capture a memory", onClick = onOpenCapture)
            }
        }
    }
}

@Composable
private fun MemoryCard(
    memory: Memory,
    score: Double?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val edgeColors = LocalEdgeColors.current
    Surface(
        shape = RoundedCornerShape(EdgeDimens.cardRadius),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
        tonalElevation = 1.dp,
        onClick = onToggle,
    ) {
        Column(
            Modifier.padding(EdgeDimens.spacingL),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = memory.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                score?.let {
                    Spacer(Modifier.width(EdgeDimens.spacingS))
                    Text(
                        text = "match %.0f%%".format((it * 100).coerceIn(0.0, 100.0)),
                        style = MaterialTheme.typography.labelSmall,
                        color = edgeColors.positive,
                    )
                }
            }
            Text(
                text = plainPreview(memory.content),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
            ) {
                StatusChip(
                    text = memory.type.name.lowercase(),
                    showDot = false,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                StatusChip(
                    text = originLabel(memory.origin),
                    showDot = false,
                    color = if (memory.origin == MemoryOrigin.CLOUD) {
                        edgeColors.accentBlue
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    containerColor = if (memory.origin == MemoryOrigin.CLOUD) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                StatusChip(
                    text = policyEligibility(memory.syncDecision),
                    showDot = false,
                    color = policyColor(memory.syncDecision),
                    containerColor = policyContainer(memory.syncDecision).copy(alpha = 0.75f),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatRelative(memory.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
            if (expanded) {
                ExpandedDetails(memory, onDelete)
            }
        }
    }
}

@Composable
private fun ExpandedDetails(memory: Memory, onDelete: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (memory.tags.isNotEmpty()) {
            Text(
                text = "tags \u00b7 ${memory.tags.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (memory.type == MemoryType.DOCUMENT) {
            Text(
                text = documentLocation(memory),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "sync \u00b7 ${syncStateLabel(memory.syncState)} \u00b7 origin ${memory.origin.name.lowercase()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (memory.origin == MemoryOrigin.CLOUD) {
            Text(
                text = "cloud \u00b7 v${memory.version} \u00b7 authority ${memory.authority ?: "\u2014"} \u00b7 updated ${formatTimestamp(memory.updatedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        memory.policyReason?.let { reason ->
            Text(
                text = "policy reason \u00b7 $reason",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (memory.syncDecision == SyncDecision.SYNC_REDACTED && !memory.redactedContent.isNullOrBlank()) {
            Text(
                text = "redacted representation: ${memory.redactedContent.take(90)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onDelete,
            modifier = Modifier.align(Alignment.End),
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Delete", color = MaterialTheme.colorScheme.error)
        }
    }
}

// ── Labels / helpers ───────────────────────────────────────────────────────

private fun originLabel(origin: MemoryOrigin): String = when (origin) {
    MemoryOrigin.LOCAL -> "local"
    MemoryOrigin.CLOUD -> "cloud"
    MemoryOrigin.SYNCED -> "synced"
}

private fun syncStateLabel(state: MemorySyncState): String = when (state) {
    MemorySyncState.LOCAL -> "local only"
    MemorySyncState.PENDING -> "pending sync"
    MemorySyncState.SYNCED -> "synced"
    MemorySyncState.FAILED -> "failed"
}

@Composable
private fun policyColor(decision: SyncDecision) = when (decision) {
    SyncDecision.LOCAL_ONLY -> MaterialTheme.colorScheme.tertiary
    SyncDecision.SYNC -> MaterialTheme.colorScheme.primary
    SyncDecision.SYNC_REDACTED -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun policyContainer(decision: SyncDecision) = when (decision) {
    SyncDecision.LOCAL_ONLY -> MaterialTheme.colorScheme.tertiaryContainer
    SyncDecision.SYNC -> MaterialTheme.colorScheme.primaryContainer
    SyncDecision.SYNC_REDACTED -> MaterialTheme.colorScheme.secondaryContainer
}

private fun policyEligibility(decision: SyncDecision): String = when (decision) {
    SyncDecision.LOCAL_ONLY -> "stays on device"
    SyncDecision.SYNC -> "sync eligible"
    SyncDecision.SYNC_REDACTED -> "syncs redacted copy"
}

private val emphasisMarkerPattern = Regex("""(\*\*|__|~~|`|#{1,6}\s)""")

/** Lightweight preview cleanup: markdown markers only, content untouched otherwise. */
private fun plainPreview(content: String): String {
    val collapsed = content.replace("\n", " ")
    return emphasisMarkerPattern.replace(collapsed, "").trim()
}

private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMillis))

private fun formatRelative(epochMillis: Long): String {
    val delta = System.currentTimeMillis() - epochMillis
    val minutes = delta / 60_000
    val hours = delta / 3_600_000
    val days = delta / 86_400_000
    return when {
        delta < 60_000 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> formatTimestamp(epochMillis)
    }
}

private fun documentLocation(memory: Memory): String = buildList {
    add("source ${memory.source}")
    memory.metadata[DocumentIngestionService.META_PAGE]?.let { add("page $it") }
    memory.metadata[DocumentIngestionService.META_SECTION]?.let { add(it) }
    memory.metadata[DocumentIngestionService.META_CHUNK_INDEX]?.let { raw ->
        val index = raw.toIntOrNull()
        add(if (index != null) "chunk ${index + 1}" else "chunk $raw")
    }
}.joinToString(" \u00b7 ")
