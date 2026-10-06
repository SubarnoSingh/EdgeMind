package com.example.EdgeMemo.presentation.ask

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.retrieval.EvidenceItem
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeCardSecondary
import com.example.EdgeMemo.presentation.components.EdgeEmptyState
import com.example.EdgeMemo.presentation.components.SectionHeader
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeType

/** Test tags for UI Phase 2 citation tests. */
object CitationDetailTags {
    const val SCREEN = "edge-citation-detail"
    const val CONTENT = "edge-citation-content"
}

/**
 * One source of an answer: the record, why it matched, what it says, and its
 * details. Every field comes from the real [com.example.EdgeMemo.core.rag.SourceReference]
 * / [EvidenceItem] retained by the Ask result; fields the domain does not
 * carry are omitted, never invented. Back is drawn by the shell; [onBack] is
 * kept for callers.
 */
@Composable
fun CitationDetailScreen(
    viewModel: AskViewModel,
    sourceIndex: Int,
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val source = state.sources.firstOrNull { it.index == sourceIndex }
    val evidence: EvidenceItem? = state.evidence.firstOrNull { it.rank == sourceIndex }
        ?: state.evidence.firstOrNull { item -> source != null && item.memory.memoryId == source.memoryId }
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(CitationDetailTags.SCREEN)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = EdgeLayout.screenPadding)
            .padding(top = 4.dp, bottom = EdgeLayout.sectionGap),
    ) {
        if (source == null) {
            // The Ask result was cleared since navigation — honest emptiness.
            EdgeEmptyState(
                title = "This source isn't available anymore",
                message = "Ask the question again to see its sources.",
            )
            return@Column
        }
        val memory = evidence?.memory

        // Subject: the record itself.
        Row(verticalAlignment = Alignment.CenterVertically) {
            CitationNumber(source.index)
            Text(
                text = "Source ${source.index} of ${state.sources.size}",
                style = EdgeType.label,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Text(
            text = source.title.ifBlank { "Untitled record" },
            style = MaterialTheme.typography.titleLarge,
            color = scheme.onSurface,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            machineTagOf(memory?.subjectKey)?.let {
                Text(it, style = EdgeType.numeric, color = scheme.onSurface)
            }
            Text(typeLabel(source.type), style = EdgeType.label, color = scheme.onSurfaceVariant)
        }

        Section("Why it matched") {
            EdgeCardSecondary(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    evidence?.matchedTerms?.takeIf { it.isNotEmpty() }
                        ?.let { Fact("Words in common", it.joinToString(", ")) }
                    Fact("Combined score", formatScore(source.score))
                    evidence?.denseScore?.let { Fact("Meaning match", formatScore(it)) }
                    evidence?.keywordScore?.let { Fact("Keyword match", formatScore(it)) }
                }
            }
        }

        Section("What it says") {
            val content = memory?.content?.takeIf { it.isNotBlank() }
                ?: source.snippet.takeIf { it.isNotBlank() }
            EdgeCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = content ?: "This record has no text.",
                    style = EdgeType.bodyEmphasis,
                    color = if (content != null) scheme.onSurface else scheme.onSurfaceVariant,
                    modifier = Modifier.testTag(CitationDetailTags.CONTENT),
                )
            }
        }

        Section("Details") {
            EdgeCardSecondary(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Fact("Source", source.source)
                    source.page?.let { Fact("Page", it.toString()) }
                    source.section?.let { Fact("Section", it) }
                    source.chunkIndex?.let { Fact("Part", (it + 1).toString()) }
                    if (memory != null) {
                        memory.subjectKey?.takeIf { it.isNotBlank() }?.let { Fact("Subject", it) }
                        Fact("Version", memory.version.toString())
                        Fact("Sync", syncLabel(memory.syncState))
                        Fact("Origin", originLabel(memory.origin))
                        if (memory.tags.isNotEmpty()) Fact("Tags", memory.tags.joinToString(", "))
                        Fact("Record id", memory.memoryId, EdgeType.code)
                    }
                    source.chunkId?.let { Fact("Chunk id", it, EdgeType.code) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.padding(top = EdgeLayout.sectionGap),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
    ) {
        SectionHeader(title = title)
        content()
    }
}

@Composable
private fun Fact(label: String, value: String, valueStyle: TextStyle = EdgeType.body) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
    ) {
        Text(
            text = label,
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(top = 1.dp),
        )
        Text(
            text = value,
            style = if (valueStyle == EdgeType.code) valueStyle.copy(fontSize = 12.sp) else valueStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.8f),
        )
    }
}

private fun syncLabel(state: MemorySyncState): String = when (state) {
    MemorySyncState.LOCAL -> "Only on this device"
    MemorySyncState.PENDING -> "Queued to sync"
    MemorySyncState.SYNCED -> "Synced"
    MemorySyncState.FAILED -> "Sync failed"
}

private fun originLabel(origin: MemoryOrigin): String = when (origin) {
    MemoryOrigin.LOCAL -> "Created on this device"
    MemoryOrigin.CLOUD -> "From the cloud"
    MemoryOrigin.SYNCED -> "Synced from the cloud"
}

/** Raw retrieval score — never presented as a percentage or confidence. */
private fun formatScore(score: Double): String =
    "%.4f".format(score).trimEnd('0').trimEnd('.', ',')
