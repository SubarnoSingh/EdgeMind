package com.example.EdgeMemo.presentation.memory

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.RetrievedMemory
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.policy.PolicyDecision
import com.example.EdgeMemo.core.sync.SyncSummary
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.domain.conflict.ConflictResolutionAction
import com.example.EdgeMemo.domain.document.DocumentIngestionService
import com.example.EdgeMemo.domain.document.IngestionStage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(viewModel: MemoryViewModel) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Memory")
                        Text(
                            "${state.memoryCount} memories \u00b7 EDGE ONLY",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("working\u2026", style = MaterialTheme.typography.labelMedium)
                }
            }

            state.error?.let { error ->
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${error::class.simpleName}: ${error.message}",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(onClick = { viewModel.consumeError() }) {
                            Text("Dismiss")
                        }
                    }
                }
            }

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

            DocumentImportSection(
                state = state.ingestion,
                onPick = viewModel::onDocumentPicked,
                onDismiss = viewModel::dismissIngestion,
            )

            SearchSection(
                query = state.query,
                onQueryChange = viewModel::onQueryChange,
                onSearch = viewModel::search,
                searchActive = state.searchActive,
                onClear = viewModel::clearSearch,
                results = state.results,
            )

            CloudKnowledgeSection(
                unresolved = state.unresolvedConflictCount,
                visible = state.conflictsVisible,
                conflicts = state.conflicts,
                pullStatus = state.pullStatus,
                isBusy = state.isBusy,
                onPull = viewModel::pullCloud,
                onToggle = viewModel::toggleConflicts,
                onResolve = viewModel::resolve,
            )

            val items = state.items
            Text(
                if (state.searchActive) "${items.size} results" else "All memories",
                style = MaterialTheme.typography.titleMedium,
            )

            SyncSummarySection(state.syncSummary)

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.memoryId }) { memory ->
                    val score = state.results.firstOrNull { it.memory.memoryId == memory.memoryId }?.score
                    MemoryCard(memory, score)
                }
            }
        }
    }
}

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
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("New memory", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                label = { Text("Content") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            policy?.let {
                Text(
                    "policy \u00b7 ${it.syncDecision.name.lowercase().replace("_", " ")}",
                    style = MaterialTheme.typography.labelMedium,
                    color = policyColor(it.syncDecision),
                )
                Text(
                    it.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text("Save to local memory")
            }
        }
    }
}

@Composable
private fun DocumentImportSection(
    state: DocumentIngestionState,
    onPick: (Uri?) -> Unit,
    onDismiss: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = onPick,
    )

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Import document", style = MaterialTheme.typography.titleMedium)
            Text(
                "PDF, Markdown or plain text \u2014 extracted, chunked and embedded on device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    launcher.launch(
                        arrayOf("application/pdf", "text/plain", "text/markdown"),
                    )
                },
                enabled = !state.isActive,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isActive) "Processing\u2026" else "Select document")
            }

            if (state.isActive || state.stage == IngestionStage.COMPLETED) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.isActive) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp))
                        Spacer(Modifier.padding(start = 8.dp))
                    }
                    Text(
                        buildString {
                            append(state.sourceName ?: "document")
                            append(" \u00b7 ")
                            append(stageLabel(state.stage))
                            if (state.stage == IngestionStage.COMPLETED) {
                                append(" \u00b7 ${state.chunkCount} chunks")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            state.error?.let { error ->
                Text(
                    "${error::class.simpleName}: ${error.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
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

@Composable
private fun SearchSection(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    searchActive: Boolean,
    onClear: () -> Unit,
    results: List<RetrievedMemory>,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Search memory", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Semantic query") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSearch) {
                    Text(if (searchActive) "Search again" else "Search")
                }
                if (searchActive) {
                    Button(onClick = onClear) {
                        Text("Clear")
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudKnowledgeSection(
    unresolved: Long,
    visible: Boolean,
    conflicts: List<Conflict>,
    pullStatus: CloudPullStatus,
    isBusy: Boolean,
    onPull: () -> Unit,
    onToggle: () -> Unit,
    onResolve: (String, ConflictResolutionAction) -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Cloud knowledge", style = MaterialTheme.typography.titleSmall)
                Button(onClick = onPull, enabled = !isBusy) {
                    Text(if (isBusy) "working\u2026" else "Pull cloud")
                }
            }
            when (val status = pullStatus) {
                CloudPullStatus.Idle -> Text(
                    "shared knowledge arrives here when connectivity returns.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CloudPullStatus.Unavailable -> Text(
                    "no cloud backend is configured \u2014 nothing was received or fabricated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                is CloudPullStatus.Success -> Text(
                    buildString {
                        append("received: ")
                        append(status.result.applied)
                        append(" new/updated \u00b7 ")
                        append(status.result.conflicts)
                        append(" conflicts \u00b7 ")
                        append(status.result.tombstoned)
                        append(" removed")
                        if (status.result.duplicates > 0) {
                            append(" \u00b7 ")
                            append(status.result.duplicates)
                            append(" duplicates skipped")
                        }
                        status.result.cursor?.let { append(" \u00b7 next \u00b7 $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is CloudPullStatus.Failed -> Text(
                    status.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onToggle, enabled = conflicts.isNotEmpty() || unresolved > 0) {
                    Text(if (visible) "Hide conflicts" else "Conflicts (${unresolved})")
                }
            }

            if (visible && conflicts.isNotEmpty()) {
                conflicts.forEach { conflict ->
                    ConflictItem(conflict, onResolve)
                }
            }
        }
    }
}

@Composable
private fun ConflictItem(
    conflict: Conflict,
    onResolve: (String, ConflictResolutionAction) -> Unit,
) {
    Card {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "conflict · ${conflict.subjectKey.ifBlank { "no subject" }}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                conflict.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ConflictSide("LOCAL", conflict.localOrigin, conflict.localVersion, conflict.localAuthority, conflict.localTitle, conflict.localContent)
            ConflictSide("CLOUD", conflict.incomingOrigin, conflict.incomingVersion, conflict.incomingAuthority, conflict.incomingTitle, conflict.incomingContent)
            conflict.state.name.let { state ->
                if (state != "UNRESOLVED") {
                    Text(
                        "${state.lowercase()} · ${conflict.resolution ?: ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (conflict.state.name == "UNRESOLVED") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        "$label · ${origin.lowercase()} · v${version ?: "?"} · authority ${authority ?: "\u2014"}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "$title \u2014 ${content.take(80)}",
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
    )
}

@Composable
private fun SyncSummarySection(summary: SyncSummary) {
    val total = summary.pending + summary.syncing + summary.synced + summary.failed
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Synchronization", style = MaterialTheme.typography.titleSmall)
            Text(
                buildString {
                    append("local only ${summary.localOnly}")
                    append(" \u00b7 pending ${summary.pending}")
                    append(" \u00b7 syncing ${summary.syncing}")
                    append(" \u00b7 synced ${summary.synced}")
                    append(" \u00b7 failed ${summary.failed}")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary.failed > 0) {
                Text(
                    "failed operations will retry automatically when the network allows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else if (total > 0 && summary.synced == total) {
                Text(
                    "all outbound operations acknowledged by the cloud.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (total == 0L && summary.localOnly > 0L) {
                Text(
                    "nothing leaves this device without policy approval.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun syncStateLabel(state: MemorySyncState): String = when (state) {
    MemorySyncState.LOCAL -> "local only"
    MemorySyncState.PENDING -> "pending sync"
    MemorySyncState.SYNCED -> "synced"
    MemorySyncState.FAILED -> "failed"
}

@Composable
private fun MemoryCard(memory: Memory, score: Double?) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    memory.type.name.lowercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    memory.tags.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                )
                score?.let {
                    Text(
                        "score %.3f".format(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Text(memory.title, style = MaterialTheme.typography.titleMedium)
            Text(
                memory.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
            )
            if (memory.type == MemoryType.DOCUMENT) {
                Text(
                    documentLocation(memory),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "origin ${memory.origin.name.lowercase()} \u00b7 sync ${syncStateLabel(memory.syncState)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (memory.origin == MemoryOrigin.CLOUD) {
                Text(
                    "cloud \u00b7 v${memory.version} \u00b7 authority ${memory.authority ?: "\u2014"} \u00b7 updated ${formatTimestamp(memory.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "policy \u00b7 ${memory.syncDecision.name.lowercase().replace("_", " ")} \u00b7 ${policyEligibility(memory)}",
                style = MaterialTheme.typography.labelSmall,
                color = policyColor(memory.syncDecision),
            )
            memory.policyReason?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (memory.syncDecision == SyncDecision.SYNC_REDACTED && !memory.redactedContent.isNullOrBlank()) {
                Text(
                    "redacted representation: ${memory.redactedContent.take(90)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun policyEligibility(memory: Memory): String = when (memory.syncDecision) {
    SyncDecision.LOCAL_ONLY -> "stays on device"
    SyncDecision.SYNC -> "sync eligible"
    SyncDecision.SYNC_REDACTED -> "syncs redacted copy"
}

@Composable
private fun policyColor(decision: SyncDecision) = when (decision) {
    SyncDecision.LOCAL_ONLY -> MaterialTheme.colorScheme.tertiary
    SyncDecision.SYNC -> MaterialTheme.colorScheme.primary
    SyncDecision.SYNC_REDACTED -> MaterialTheme.colorScheme.secondary
}

private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMillis))

private fun documentLocation(memory: Memory): String = buildList {
    add("source ${memory.source}")
    memory.metadata[DocumentIngestionService.META_PAGE]?.let { add("page $it") }
    memory.metadata[DocumentIngestionService.META_SECTION]?.let { add(it) }
    memory.metadata[DocumentIngestionService.META_CHUNK_INDEX]?.let { raw ->
        val index = raw.toIntOrNull()
        add(if (index != null) "chunk ${index + 1}" else "chunk $raw")
    }
}.joinToString(" \u00b7 ")