package com.example.EdgeMemo.presentation.machines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryMetadataKeys
import com.example.EdgeMemo.presentation.components.EdgeDimens
import com.example.EdgeMemo.presentation.components.EdgeEmptyState
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.EdgeListGroup
import com.example.EdgeMemo.presentation.components.EdgeLoadingState
import com.example.EdgeMemo.presentation.components.MarkdownBody
import com.example.EdgeMemo.presentation.components.SectionHeader
import com.example.EdgeMemo.presentation.components.StatusDot
import com.example.EdgeMemo.presentation.components.relativeTimeLabel
import com.example.EdgeMemo.presentation.shell.LoadableState
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeType

/** UI Phase 3 record-detail test tags. */
object RecordDetailTags {
    const val SCREEN = "edge-record-detail"
    const val CONTENT = "edge-record-detail-content"
    const val EMPTY = "edge-record-detail-empty"
}

/**
 * Full record view for one stored knowledge record. Every field shown exists
 * on the domain `Memory`; document/chunk provenance comes from the real
 * metadata keys the ingestion/retrieval layers already write. Back is drawn
 * by the shell header; [onBack] stays for callers.
 */
@Composable
fun RecordDetailScreen(
    viewModel: RecordDetailViewModel,
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    val state by viewModel.uiState.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(RecordDetailTags.SCREEN)
            .verticalScroll(rememberScrollState())
            .padding(
                start = EdgeLayout.screenPadding,
                end = EdgeLayout.screenPadding,
                top = EdgeLayout.compactGap,
                bottom = EdgeLayout.sectionGap,
            ),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.sectionGap),
    ) {
        when (val s = state) {
            LoadableState.Loading -> EdgeLoadingState("Loading record…")
            is LoadableState.Failed -> EdgeErrorState(s.message, onRetry = viewModel::refresh)
            is LoadableState.Ready -> {
                val record = s.value
                if (record == null) {
                    EdgeEmptyState(
                        title = "This record is gone",
                        message = "It was deleted or replaced by a newer version.",
                        modifier = Modifier.testTag(RecordDetailTags.EMPTY),
                    )
                } else {
                    RecordHeader(record, nowMillis())
                    RecordBody(record)
                    FactGroup("About", aboutFacts(record))
                    FactGroup("Source", sourceFacts(record))
                    FactGroup("Sync", syncFacts(record))
                    if (record.metadata.isNotEmpty()) FactGroup("Document", documentFacts(record))
                    FactGroup("Identifiers", identifierFacts(record), valueStyle = EdgeType.code)
                }
            }
        }
    }
}

@Composable
private fun RecordHeader(record: Memory, nowMillis: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        Text(
            text = record.title.ifBlank { record.memoryId.take(8) },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingL),
        ) {
            Text(
                text = AssetModel.categoryOf(record.type).singular,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = recordTimeLabel(record.updatedAt, nowMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val sync = recordSyncStatus(record.syncState, long = true)
            StatusDot(status = sync.status, label = sync.label)
        }
    }
}

@Composable
private fun RecordBody(record: Memory) {
    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .testTag(RecordDetailTags.CONTENT),
    ) {
        if (record.content.isBlank()) {
            Text(
                text = "No text was saved with this record.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            MarkdownBody(record.content)
        }
    }
}

private fun aboutFacts(record: Memory): List<Pair<String, String>> = buildList {
    val category = AssetModel.categoryOf(record.type)
    add("Type" to if (category == AssetModel.AssetRecordCategory.OTHER) humanize(record.type.name) else category.singular)
    record.subjectKey?.takeIf { it.isNotBlank() }?.let { add("Subject" to it) }
    if (record.tags.isNotEmpty()) add("Tags" to record.tags.joinToString(", "))
    add("Importance" to record.importance.toString())
    add("Sensitivity" to humanize(record.sensitivity.name))
}

private fun sourceFacts(record: Memory): List<Pair<String, String>> = buildList {
    add("Source" to record.source)
    add("Origin" to humanize(record.origin.name))
    record.authority?.takeIf { it.isNotBlank() }?.let { add("Authority" to it) }
    add("Created" to formatRecordTimestamp(record.createdAt))
    add("Updated" to formatRecordTimestamp(record.updatedAt))
}

private fun syncFacts(record: Memory): List<Pair<String, String>> = buildList {
    add("Policy" to humanize(record.syncDecision.name))
    record.policyReason?.takeIf { it.isNotBlank() }?.let { add("Why" to it) }
    record.redactedTitle?.takeIf { it.isNotBlank() }?.let { add("Shared title" to it) }
    add("State" to recordSyncStatus(record.syncState, long = true).label)
    add("Version" to "v${record.version}")
    record.supersedes?.takeIf { it.isNotBlank() }?.let { add("Replaces" to it) }
}

private fun documentFacts(record: Memory): List<Pair<String, String>> = buildList {
    val meta = record.metadata
    meta[MemoryMetadataKeys.DOCUMENT_TITLE]?.let { add("Document" to it) }
    meta[MemoryMetadataKeys.SOURCE_NAME]?.let { add("File" to it) }
    meta[MemoryMetadataKeys.PAGE]?.let { add("Page" to it) }
    meta[MemoryMetadataKeys.SECTION]?.let { add("Section" to it) }
    meta[MemoryMetadataKeys.CHUNK_INDEX]?.toIntOrNull()?.let { index ->
        val total = meta[MemoryMetadataKeys.CHUNK_COUNT]
        add("Part" to if (total != null) "${index + 1} of $total" else "${index + 1}")
    }
    meta[MemoryMetadataKeys.FORMAT]?.let { add("Format" to it) }
    // Any additional real metadata is shown as-is (keys are the domain's).
    meta.entries
        .filter { it.key !in KNOWN_METADATA_KEYS }
        .forEach { (key, value) -> add(humanizeKey(key) to value) }
}

private fun identifierFacts(record: Memory): List<Pair<String, String>> = buildList {
    add("Record" to record.memoryId)
    record.chunkId?.takeIf { it.isNotBlank() }?.let { add("Chunk" to it) }
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
private fun FactGroup(
    title: String,
    facts: List<Pair<String, String>>,
    valueStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    if (facts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        SectionHeader(title)
        EdgeListGroup(facts) { (label, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EdgeDimens.spacingL, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingM),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.38f),
                )
                Text(
                    text = value,
                    style = valueStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(0.62f),
                )
            }
        }
    }
}

/** `LOCAL_ONLY` -> `Local only`. Enum names are identifiers, not copy. */
private fun humanize(enumName: String): String =
    enumName.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

/** `recordedDate` -> `Recorded date`. */
private fun humanizeKey(key: String): String =
    key.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ')
        .lowercase().replaceFirstChar { it.uppercase() }

/** UTC-formatted stable timestamps; deterministic across devices and tests. */
fun formatRecordTimestamp(epochMillis: Long): String {
    if (epochMillis <= 0L) return "—"
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(java.util.Date(epochMillis))
}

/**
 * One time label per row: relative while recent ("2h ago"), a plain date once
 * it's older than a week ("12 Sep 2026", UTC like [formatRecordTimestamp]).
 */
internal fun recordTimeLabel(epochMillis: Long, nowMillis: Long): String {
    if (epochMillis <= 0L) return "—"
    if (nowMillis - epochMillis < 7 * 86_400_000L) return relativeTimeLabel(epochMillis, nowMillis)
    return java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(java.util.Date(epochMillis))
}
