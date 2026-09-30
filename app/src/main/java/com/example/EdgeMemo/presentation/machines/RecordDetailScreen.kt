package com.example.EdgeMemo.presentation.machines

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryMetadataKeys
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeCardSecondary
import com.example.EdgeMemo.presentation.components.EdgeEmptyState
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.EdgeLoadingState
import com.example.EdgeMemo.presentation.components.MarkdownBody
import com.example.EdgeMemo.presentation.components.StatusChip
import com.example.EdgeMemo.presentation.components.TechLabel
import com.example.EdgeMemo.presentation.shell.LoadableState
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus
import com.example.EdgeMemo.ui.theme.EdgeType
import com.example.EdgeMemo.ui.theme.statusColor

/** UI Phase 3 record-detail test tags. */
object RecordDetailTags {
    const val SCREEN = "edge-record-detail"
    const val CONTENT = "edge-record-detail-content"
    const val BACK = "edge-record-detail-back"
    const val EMPTY = "edge-record-detail-empty"
}

/**
 * Full record view for one stored knowledge record. Every field shown exists
 * on the domain `Memory`; document/chunk provenance comes from the real
 * metadata keys the ingestion/retrieval layers already write. No storage
 * internals (shards, point ids beyond the record id, JNI) are shown.
 */
@Composable
fun RecordDetailScreen(
    viewModel: RecordDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    val state by viewModel.uiState.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(RecordDetailTags.SCREEN)
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
                    .testTag(RecordDetailTags.BACK)
                    .semantics { contentDescription = "Back to the asset workspace" }
                    .clickable(onClick = onBack)
                    .padding(EdgeLayout.compactGap),
            )
            Spacer(Modifier.weight(1f))
            TechLabel(text = "RECORD")
        }
        when (val s = state) {
            LoadableState.Loading -> EdgeLoadingState("Reading record\u2026")
            is LoadableState.Failed -> EdgeErrorState(s.message, onRetry = viewModel::refresh)
            is LoadableState.Ready -> {
                val record = s.value
                if (record == null) {
                    EdgeEmptyState(
                        title = "Record is no longer available",
                        message = "It may have been deleted or superseded.",
                        modifier = Modifier.testTag(RecordDetailTags.EMPTY),
                    )
                } else {
                    RecordHeader(record)
                    RecordContent(record)
                    RecordClassification(record)
                    RecordProvenance(record)
                    RecordState(record)
                    if (record.metadata.isNotEmpty()) RecordMetadata(record)
                    RecordIdentity(record)
                }
            }
        }
    }
}

@Composable
private fun RecordHeader(record: Memory) {
    EdgeCard {
        Text(
            text = AssetModel.categoryOf(record.type).label.uppercase(),
            style = EdgeType.label,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.heightIn(min = 2.dp))
        Text(
            text = record.title.ifBlank { record.memoryId.take(8) },
            style = EdgeType.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val syncStatus = when (record.syncState) {
            MemorySyncState.PENDING -> EdgeStatus.WARNING to "pending sync"
            MemorySyncState.FAILED -> EdgeStatus.CRITICAL to "sync failed"
            MemorySyncState.SYNCED -> EdgeStatus.SYNCED to "synced"
            MemorySyncState.LOCAL -> EdgeStatus.NEUTRAL to "local only"
        }
        Spacer(Modifier.padding(vertical = 2.dp))
        StatusChip(text = syncStatus.second, color = syncStatus.first.statusColor())
    }
}

@Composable
private fun RecordContent(record: Memory) {
    TechLabel(text = "CONTENT")
    EdgeCardSecondary(
        modifier = Modifier.testTag(RecordDetailTags.CONTENT),
    ) {
        if (record.content.isBlank()) {
            Text(
                text = "(no text stored)",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            MarkdownBody(record.content)
        }
    }
}

@Composable
private fun RecordClassification(record: Memory) {
    Section("CLASSIFICATION") {
        Fact("Type", record.type.name.lowercase().replace('_', ' '))
        record.subjectKey?.takeIf { it.isNotBlank() }?.let { Fact("Subject", it) }
        if (record.tags.isNotEmpty()) Fact("Tags", record.tags.joinToString(", "))
        Fact("Importance", record.importance.toString())
        Fact("Sensitivity", record.sensitivity.name.lowercase())
    }
}

@Composable
private fun RecordProvenance(record: Memory) {
    Section("PROVENANCE") {
        Fact("Source", record.source)
        Fact("Origin", record.origin.name.lowercase())
        record.authority?.takeIf { it.isNotBlank() }?.let { Fact("Authority", it) }
        Fact("Created", formatRecordTimestamp(record.createdAt))
        Fact("Updated", formatRecordTimestamp(record.updatedAt))
    }
}

@Composable
private fun RecordState(record: Memory) {
    Section("POLICY & SYNC") {
        Fact("Sync decision", record.syncDecision.name.lowercase().replace('_', ' '))
        record.policyReason?.takeIf { it.isNotBlank() }?.let { Fact("Policy reason", it) }
        record.redactedTitle?.takeIf { it.isNotBlank() }?.let {
            Fact("Synced title", it)
        }
        Fact("Record sync state", record.syncState.name.lowercase())
        Fact("Version", "v${record.version}")
        record.supersedes?.takeIf { it.isNotBlank() }?.let { Fact("Supersedes", it) }
    }
}

@Composable
private fun RecordMetadata(record: Memory) {
    Section("DOCUMENT / EVIDENCE") {
        record.metadata[MemoryMetadataKeys.DOCUMENT_TITLE]?.let { Fact("Document", it) }
        record.metadata[MemoryMetadataKeys.SOURCE_NAME]?.let { Fact("File", it) }
        record.metadata[MemoryMetadataKeys.PAGE]?.let { Fact("Page", it) }
        record.metadata[MemoryMetadataKeys.SECTION]?.let { Fact("Section", it) }
        record.metadata[MemoryMetadataKeys.CHUNK_INDEX]?.let {
            it.toIntOrNull()?.plus(1)?.toString()?.let { n -> Fact("Chunk", n) }
        }
        record.metadata[MemoryMetadataKeys.CHUNK_COUNT]?.let { Fact("Chunks total", it) }
        record.metadata[MemoryMetadataKeys.FORMAT]?.let { Fact("Format", it) }
        // Any additional real metadata is shown as-is (keys are the domain's).
        record.metadata.entries
            .filter { it.key !in KNOWN_METADATA_KEYS }
            .forEach { (key, value) -> Fact(key, value) }
    }
}

@Composable
private fun RecordIdentity(record: Memory) {
    Section("RECORD") {
        Fact("Record id", record.memoryId)
        record.chunkId?.takeIf { it.isNotBlank() }?.let { Fact("Chunk id", it) }
    }
}

private val KNOWN_METADATA_KEYS = setOf(
    MemoryMetadataKeys.DOCUMENT_ID,
    MemoryMetadataKeys.DOCUMENT_TITLE,
    MemoryMetadataKeys.SOURCE_URI,
    MemoryMetadataKeys.SOURCE_NAME,
    MemoryMetadataKeys.CHUNK_INDEX,
    MemoryMetadataKeys.CHUNK_COUNT,
    MemoryMetadataKeys.FORMAT,
    MemoryMetadataKeys.PAGE,
    MemoryMetadataKeys.SECTION,
)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap)) {
        TechLabel(text = title, color = MaterialTheme.colorScheme.onSurfaceVariant)
        EdgeCardSecondary { content() }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
    ) {
        Text(
            text = label,
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = EdgeType.numeric,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(2f),
        )
    }
}

/** UTC-formatted stable timestamps; deterministic across devices and tests. */
fun formatRecordTimestamp(epochMillis: Long): String {
    if (epochMillis <= 0L) return "\u2014"
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(java.util.Date(epochMillis))
}
