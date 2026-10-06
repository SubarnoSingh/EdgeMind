package com.example.EdgeMemo.presentation.memory

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.domain.conflict.ConflictResolutionAction
import com.example.EdgeMemo.domain.document.DocumentIngestionService
import com.example.EdgeMemo.domain.document.IngestionStage
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeCardSecondary
import com.example.EdgeMemo.presentation.components.EdgeDimens
import com.example.EdgeMemo.presentation.components.EdgeEmptyState
import com.example.EdgeMemo.presentation.components.EdgeListGroup
import com.example.EdgeMemo.presentation.components.PillButton
import com.example.EdgeMemo.presentation.components.SectionHeader
import com.example.EdgeMemo.presentation.components.StatusChip
import com.example.EdgeMemo.presentation.components.TonalPill
import com.example.EdgeMemo.presentation.components.relativeTimeLabel
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus
import com.example.EdgeMemo.ui.theme.EdgeType
import com.example.EdgeMemo.ui.theme.LocalEdgeColors
import com.example.EdgeMemo.ui.theme.statusStyleFor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Records browser (pushed route). The shell draws Back; this screen titles itself. */
@Composable
fun MemoryScreen(viewModel: MemoryViewModel) {
    val state by viewModel.uiState.collectAsState()
    var captureOpen by rememberSaveable { mutableStateOf(false) }
    val expandedMemories = remember { mutableStateMapOf<String, Boolean>() }

    // The ViewModel is Activity-scoped and shared with Settings; it only
    // reloads from its own actions. Mutations from other paths (explicit
    // "Save to memory" on the Ask screen, cloud answers cached, conflict
    // resolution) write through the repository without notifying it, so the
    // list refreshes every time this screen becomes visible.
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = viewModel::onDocumentPicked,
    )
    val launchImport = {
        importLauncher.launch(arrayOf("application/pdf", "text/plain", "text/markdown"))
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = EdgeLayout.screenPadding),
            verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingM),
        ) {
            HeaderRow(state)
            SearchBar(
                query = state.query,
                onQueryChange = viewModel::onQueryChange,
                onSearch = viewModel::search,
                onClear = viewModel::clearSearch,
                searchActive = state.searchActive,
            )
            ActionRow(
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
                start = EdgeLayout.screenPadding,
                end = EdgeLayout.screenPadding,
                top = EdgeDimens.spacingL,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
        ) {
            state.error?.let { error ->
                item {
                    NoticeRow(
                        text = error.message ?: "That didn't work. Try again.",
                        color = MaterialTheme.colorScheme.error,
                        onDismiss = viewModel::consumeError,
                    )
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
                        onCancel = { captureOpen = false },
                    )
                }
            }

            if (state.ingestion.stage != IngestionStage.IDLE || state.ingestion.error != null) {
                item { IngestionStatus(state.ingestion, viewModel::dismissIngestion) }
            }

            if (state.pullStatus !is CloudPullStatus.Idle) {
                item {
                    NoticeRow(
                        text = cloudPullSummary(state.pullStatus),
                        color = if (state.pullStatus is CloudPullStatus.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }

            if (state.conflictsVisible && (state.conflicts.isNotEmpty() || state.unresolvedConflictCount > 0)) {
                item { ConflictsSection(conflicts = state.conflicts, onResolve = viewModel::resolve) }
            }

            item { SyncSummaryRow(state.syncSummary) }

            item {
                SectionHeader(
                    title = if (state.searchActive) {
                        "${state.items.size} ${if (state.items.size == 1) "match" else "matches"}"
                    } else {
                        "All records"
                    },
                    modifier = Modifier.padding(top = EdgeDimens.spacingM),
                )
            }

            if (state.items.isEmpty()) {
                item {
                    if (state.searchActive) {
                        EdgeEmptyState(
                            title = "No matching records",
                            message = "Try other words, or clear the search.",
                            action = { TonalPill(text = "Clear search", onClick = viewModel::clearSearch) },
                        )
                    } else {
                        EdgeEmptyState(
                            title = "No records yet",
                            message = "Add a record or import a manual to get started.",
                            action = { PillButton(text = "New record", onClick = { captureOpen = true }) },
                        )
                    }
                }
            } else {
                // ponytail: one non-lazy group for the whole list; split into
                // per-row lazy items with shaped ends if record counts reach the thousands.
                item {
                    EdgeListGroup(state.items) { memory ->
                        val score = state.results.firstOrNull { it.memory.memoryId == memory.memoryId }?.score
                        RecordRow(
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
    }
}

@Composable
private fun HeaderRow(state: MemoryUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = EdgeDimens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Records", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = "${state.memoryCount} on this device",
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
                "Search records, tags or part numbers",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingIcon = {
            if (searchActive || query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Filled.Clear,
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
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun ActionRow(
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
            if (captureOpen) {
                TonalPill(text = "Close form", onClick = onToggleCapture)
            } else {
                PillButton(text = "New record", onClick = onToggleCapture)
            }
            TonalPill(
                text = if (importEnabled) "Import file" else "Importing",
                onClick = onImport,
                enabled = importEnabled,
            )
            TonalPill(text = "Pull from cloud", onClick = onPullCloud, enabled = !pullBusy)
            if (unresolvedConflicts > 0 || conflictsVisible) {
                TonalPill(
                    text = if (conflictsVisible) "Hide conflicts" else "Conflicts ($unresolvedConflicts)",
                    onClick = onToggleConflicts,
                )
            }
        }
        // Trailing fade: the row scrolls when actions overflow. Not clickable.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(28.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                    ),
                ),
        )
    }
}

/** One-line notice with an optional dismiss (errors, cloud pull results). */
@Composable
private fun NoticeRow(text: String, color: Color, onDismiss: (() -> Unit)? = null) {
    EdgeCardSecondary(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
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
    onCancel: () -> Unit,
) {
    EdgeCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingM)) {
            SectionHeader(title = "New record")
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                label = { Text("Title") },
                singleLine = true,
                shape = RoundedCornerShape(EdgeDimens.inputRadius),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                label = { Text("Details") },
                minLines = 3,
                shape = RoundedCornerShape(EdgeDimens.inputRadius),
                modifier = Modifier.fillMaxWidth(),
            )
            FieldLabel("Type")
            ChoiceRow {
                listOf(
                    MemoryType.NOTE,
                    MemoryType.OBSERVATION,
                    MemoryType.REPAIR,
                    MemoryType.EVENT,
                    MemoryType.PROCEDURE,
                ).forEach { candidate ->
                    SquareChoiceChip(
                        label = typeLabel(candidate),
                        selected = type == candidate,
                        onClick = { onTypeChange(candidate) },
                    )
                }
            }
            FieldLabel("Sync")
            ChoiceRow {
                SquareChoiceChip("Let the app decide", userChoice == null, { onUserSyncChoiceChange(null) })
                SyncDecision.entries.forEach { candidate ->
                    SquareChoiceChip(
                        label = syncChoiceLabel(candidate),
                        selected = userChoice == candidate,
                        onClick = { onUserSyncChoiceChange(candidate) },
                    )
                }
            }
            policy?.let { PolicyLine(it.syncDecision, it.reason) }
            Row(horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
                PillButton(
                    text = "Save record",
                    onClick = onSave,
                    enabled = title.isNotBlank() || content.isNotBlank(),
                )
                TonalPill(text = "Cancel", onClick = onCancel)
            }
        }
    }
}

// ── Import / ingestion ─────────────────────────────────────────────────────

@Composable
private fun IngestionStatus(state: DocumentIngestionState, onDismiss: () -> Unit) {
    if (state.stage == IngestionStage.IDLE && state.error == null) return
    val edge = LocalEdgeColors.current
    EdgeCardSecondary(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingM)) {
            if (state.isActive) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = state.sourceName ?: "Document",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stageLabel(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state.stage) {
                        IngestionStage.COMPLETED -> edge.positive
                        IngestionStage.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                state.error?.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (!state.isActive) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

private fun stageLabel(state: DocumentIngestionState): String = when (state.stage) {
    IngestionStage.IDLE -> ""
    IngestionStage.SELECTING -> "Opening file"
    IngestionStage.EXTRACTING -> "Reading text"
    IngestionStage.CHUNKING -> "Splitting into sections"
    IngestionStage.EMBEDDING -> "Indexing for search"
    IngestionStage.STORING -> "Saving on this device"
    IngestionStage.COMPLETED ->
        "Saved as ${state.chunkCount} searchable ${if (state.chunkCount == 1) "section" else "sections"}"
    IngestionStage.FAILED -> "Import failed"
}

// ── Cloud pull / conflicts ─────────────────────────────────────────────────

/** Plain summary of the real cloud pull outcome. Shared with Settings. */
internal fun cloudPullSummary(status: CloudPullStatus): String = when (status) {
    CloudPullStatus.Idle -> "Team updates arrive when you're back online."
    CloudPullStatus.Unavailable -> "No cloud server is set up, so nothing was pulled."
    is CloudPullStatus.Success -> buildString {
        val r = status.result
        append("Got ${r.applied} new or updated ${if (r.applied == 1) "record" else "records"}.")
        if (r.conflicts > 0) append(" ${r.conflicts} to review.")
        if (r.tombstoned > 0) append(" ${r.tombstoned} removed.")
        if (r.duplicates > 0) append(" ${r.duplicates} already here.")
    }
    is CloudPullStatus.Failed -> "Pull failed: ${status.message}"
}

@Composable
private fun ConflictsSection(
    conflicts: List<Conflict>,
    onResolve: (String, ConflictResolutionAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        SectionHeader(title = "Conflicts", subtitle = "Both versions stay until you pick one.")
        EdgeListGroup(conflicts) { conflict -> ConflictRow(conflict, onResolve) }
    }
}

@Composable
private fun ConflictRow(
    conflict: Conflict,
    onResolve: (String, ConflictResolutionAction) -> Unit,
) {
    Column(
        Modifier.padding(horizontal = EdgeDimens.spacingL, vertical = EdgeDimens.spacingM),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = machineTag(conflict.subjectKey) ?: "No machine",
            style = EdgeType.numeric,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(conflict.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ConflictSide("This device", conflict.localVersion, conflict.localAuthority, conflict.localTitle)
        ConflictSide("Cloud", conflict.incomingVersion, conflict.incomingAuthority, conflict.incomingTitle)
        if (conflict.state.name != "UNRESOLVED") {
            Text(
                text = "Resolved" + (conflict.resolution?.let { ": ${it.lowercase().replace('_', ' ')}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = LocalEdgeColors.current.positive,
            )
        } else {
            Row {
                TextButton(onClick = { onResolve(conflict.conflictId, ConflictResolutionAction.KEEP_LOCAL) }) {
                    Text("Keep this device's")
                }
                TextButton(onClick = { onResolve(conflict.conflictId, ConflictResolutionAction.KEEP_CLOUD) }) {
                    Text("Use cloud")
                }
                TextButton(onClick = { onResolve(conflict.conflictId, ConflictResolutionAction.DISMISS) }) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
private fun ConflictSide(label: String, version: Int?, authority: String?, title: String) {
    Column {
        Text(
            text = buildString {
                append(label)
                version?.let { append(", version $it") }
                authority?.let { append(", from $it") }
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title.ifBlank { "Untitled" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Sync summary ───────────────────────────────────────────────────────────

@Composable
private fun SyncSummaryRow(summary: SyncSummary) {
    val chips = listOf(
        Triple(summary.pending, "queued to sync", EdgeStatus.WARNING),
        Triple(summary.syncing, "syncing", EdgeStatus.SYNCING),
        Triple(summary.synced, "synced", EdgeStatus.SYNCED),
        Triple(summary.failed, "failed", EdgeStatus.CRITICAL),
        Triple(summary.localOnly, "only on this device", EdgeStatus.NEUTRAL),
    ).filter { it.first > 0 }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
        ) {
            if (chips.isEmpty()) {
                StatusChip(text = "Nothing queued to sync")
            }
            chips.forEach { (count, label, status) ->
                val style = statusStyleFor(status)
                StatusChip(text = "$count $label", color = style.onContainer, containerColor = style.container)
            }
        }
        if (summary.failed > 0) {
            Text(
                "Failed items retry when the network is back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Records ────────────────────────────────────────────────────────────────

@Composable
private fun RecordRow(
    memory: Memory,
    score: Double?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val edge = LocalEdgeColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = EdgeDimens.spacingL, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = memory.title.ifBlank { "Untitled record" },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            machineTag(memory.subjectKey)?.let {
                Text(it, style = EdgeType.numeric, color = MaterialTheme.colorScheme.onSurface)
            }
            MetaText(typeLabel(memory.type))
            if (memory.origin == MemoryOrigin.CLOUD) {
                Text("From cloud", style = MaterialTheme.typography.labelMedium, color = edge.accentBlue)
            }
            MetaText(relativeTimeLabel(memory.updatedAt))
            score?.let {
                MetaText("%.0f%% match".format((it * 100).coerceIn(0.0, 100.0)))
            }
            Spacer(Modifier.weight(1f))
            PolicyChip(memory.syncDecision)
        }
        Text(
            text = plainPreview(memory.content),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (expanded) {
            ExpandedDetails(memory, onDelete)
        }
    }
}

@Composable
private fun MetaText(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ExpandedDetails(memory: Memory, onDelete: () -> Unit) {
    Column(
        Modifier.padding(top = EdgeDimens.spacingXs),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (memory.tags.isNotEmpty()) DetailLine("Tags: ${memory.tags.joinToString(", ")}")
        if (memory.type == MemoryType.DOCUMENT) DetailLine(documentLocation(memory))
        DetailLine(syncStateLabel(memory.syncState))
        if (memory.origin == MemoryOrigin.CLOUD) {
            DetailLine(
                "Cloud version ${memory.version}" +
                    (memory.authority?.let { ", from $it" } ?: "") +
                    ", updated ${formatTimestamp(memory.updatedAt)}",
            )
        }
        memory.policyReason?.let { PolicyLine(memory.syncDecision, it) }
        if (memory.syncDecision == SyncDecision.SYNC_REDACTED && !memory.redactedContent.isNullOrBlank()) {
            val copy = memory.redactedContent
            DetailLine("What syncs: ${if (copy.length > 160) copy.take(160).trimEnd() + "…" else copy}")
        }
        TextButton(
            onClick = onDelete,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("Delete record", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DetailLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ── Shared record-form pieces (also used by the record composer) ───────────

/** Squared single-select chip, 48dp tall for gloved taps. Iris marks the selection. */
@Composable
internal fun SquareChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = modifier
            .heightIn(min = EdgeDimens.minTouch)
            .semantics { contentDescription = label },
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 14.dp),
            )
        }
    }
}

@Composable
internal fun ChoiceRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
        verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
    ) { content() }
}

@Composable
internal fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
}

/** Policy chip only where it adds information: normal sync gets none. */
@Composable
internal fun PolicyChip(decision: SyncDecision) {
    val edge = LocalEdgeColors.current
    when (decision) {
        SyncDecision.LOCAL_ONLY -> {
            val style = statusStyleFor(EdgeStatus.WARNING)
            StatusChip(text = "Device only", color = style.onContainer, containerColor = style.container, showDot = false)
        }
        SyncDecision.SYNC_REDACTED -> StatusChip(
            text = "Redacted copy syncs",
            color = edge.accentViolet,
            containerColor = edge.accentViolet.copy(alpha = 0.16f),
            showDot = false,
        )
        SyncDecision.SYNC -> Unit
    }
}

/** "Stays on this device: detected access or credential information." */
@Composable
internal fun PolicyLine(decision: SyncDecision, reason: String) {
    val edge = LocalEdgeColors.current
    val color = when (decision) {
        SyncDecision.LOCAL_ONLY -> edge.accentAmber
        SyncDecision.SYNC_REDACTED -> edge.accentViolet
        SyncDecision.SYNC -> edge.accentBlue
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(EdgeLayout.statusDotSize)
                .background(color, RoundedCornerShape(50)),
        )
        Text(
            text = plainPolicyText(decision, reason),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

internal fun policyHeadline(decision: SyncDecision): String = when (decision) {
    SyncDecision.LOCAL_ONLY -> "Stays on this device"
    SyncDecision.SYNC -> "Syncs to your team"
    SyncDecision.SYNC_REDACTED -> "A redacted copy syncs, the original stays here"
}

// ponytail: rewords the engine's "<why> — <outcome>" reason by keeping the
// part before the dash; if engine reasons change shape, give PolicyDecision a
// separate short-why field instead.
internal fun plainPolicyText(decision: SyncDecision, reason: String): String {
    val why = when {
        reason.startsWith("User explicitly requested") -> "you chose this"
        else -> reason.substringBefore(" — ").trim().trimEnd('.').replaceFirstChar { it.lowercase() }
    }
    return if (why.isBlank()) "${policyHeadline(decision)}." else "${policyHeadline(decision)}: $why."
}

internal fun typeLabel(type: MemoryType): String = when (type) {
    MemoryType.DOCUMENT -> "Document"
    MemoryType.NOTE -> "Note"
    MemoryType.OBSERVATION -> "Observation"
    MemoryType.PROCEDURE -> "Procedure"
    MemoryType.REPAIR -> "Repair"
    MemoryType.EVENT -> "Event"
    MemoryType.CLOUD_KNOWLEDGE -> "Shared knowledge"
}

private fun syncChoiceLabel(decision: SyncDecision): String = when (decision) {
    SyncDecision.LOCAL_ONLY -> "Device only"
    SyncDecision.SYNC -> "Sync"
    SyncDecision.SYNC_REDACTED -> "Redacted copy"
}

/** "p-101/observation" -> "P-101"; null when the record has no machine. */
internal fun machineTag(subjectKey: String?): String? =
    subjectKey?.substringBefore('/')?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()

private fun syncStateLabel(state: MemorySyncState): String = when (state) {
    MemorySyncState.LOCAL -> "Only on this device"
    MemorySyncState.PENDING -> "Queued to sync"
    MemorySyncState.SYNCED -> "Synced"
    MemorySyncState.FAILED -> "Sync failed, will retry"
}

private val emphasisMarkerPattern = Regex("""(\*\*|__|~~|`|#{1,6}\s)""")

/** Lightweight preview cleanup: markdown markers only, content untouched otherwise. */
private fun plainPreview(content: String): String {
    val collapsed = content.replace("\n", " ")
    return emphasisMarkerPattern.replace(collapsed, "").trim()
}

private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date(epochMillis))

private fun documentLocation(memory: Memory): String = buildList {
    add("From ${memory.source}")
    memory.metadata[DocumentIngestionService.META_PAGE]?.let { add("page $it") }
    memory.metadata[DocumentIngestionService.META_SECTION]?.let { add(it) }
    memory.metadata[DocumentIngestionService.META_CHUNK_INDEX]?.let { raw ->
        val index = raw.toIntOrNull()
        add(if (index != null) "part ${index + 1}" else "part $raw")
    }
}.joinToString(", ")
