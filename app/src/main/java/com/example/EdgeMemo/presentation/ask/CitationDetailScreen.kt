package com.example.EdgeMemo.presentation.ask

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
import com.example.EdgeMemo.core.rag.SourceReference
import com.example.EdgeMemo.core.retrieval.EvidenceItem
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeCardSecondary
import com.example.EdgeMemo.presentation.components.EdgeEmptyState
import com.example.EdgeMemo.presentation.components.TechLabel
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeType

/** Test tags for UI Phase 2 citation tests. */
object CitationDetailTags {
    const val SCREEN = "edge-citation-detail"
    const val CONTENT = "edge-citation-content"
    const val BACK = "edge-citation-back"
}

/**
 * Full traceability for one citation: exactly what was retrieved and why it
 * ranked, from the retained Ask result. Every field comes from the real
 * [SourceReference] / [EvidenceItem] carried by the domain; fields the
 * domain does not carry are omitted, never invented. Identifiers are shown
 * only where meaningful to a technician (record/chunk id, subject) — never
 * storage internals.
 */
@Composable
fun CitationDetailScreen(
    viewModel: AskViewModel,
    sourceIndex: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val source = state.sources.firstOrNull { it.index == sourceIndex }
    val evidence: EvidenceItem? = state.evidence.firstOrNull { it.rank == sourceIndex }
        ?: state.evidence.firstOrNull { item -> source != null && item.memory.memoryId == source.memoryId }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(CitationDetailTags.SCREEN)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = EdgeLayout.screenPadding, vertical = EdgeLayout.cardGap),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "← Back",
                style = EdgeType.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag(CitationDetailTags.BACK)
                    .semantics { contentDescription = "Back to the answer and evidence list" }
                    .clickable(onClick = onBack)
                    .padding(end = EdgeLayout.cardGap, top = 8.dp, bottom = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            TechLabel(text = "CITATION [$sourceIndex]")
        }

        if (source == null) {
            // The Ask result was cleared since navigation — honest emptiness.
            EdgeEmptyState(
                title = "Citation is no longer available",
                message = "Ask a new question to retrieve evidence again.",
            )
            return@Column
        }

        EdgeCard {
            Text(
                text = source.title.ifBlank { "Untitled record" },
                style = EdgeType.sectionTitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.padding(vertical = 2.dp))
            Text(
                text = source.type.name.lowercase().replace('_', ' '),
                style = EdgeType.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("LOCATION") {
            Fact("Source", source.source)
            source.page?.let { Fact("Page", it.toString()) }
            source.section?.let { Fact("Section", it) }
            source.chunkIndex?.let { Fact("Chunk", (it + 1).toString()) }
            source.chunkId?.let { Fact("Chunk id", it) }
            evidence?.memory?.subjectKey?.takeIf { it.isNotBlank() }
                ?.let { Fact("Asset subject", it) }
        }

        Section("RETRIEVED CONTENT") {
            val content = evidence?.memory?.content?.takeIf { it.isNotBlank() }
                ?: source.snippet.takeIf { it.isNotBlank() }
            Text(
                text = content ?: "(no text stored on this record)",
                style = EdgeType.body,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(CitationDetailTags.CONTENT),
            )
        }

        Section("RETRIEVAL BASIS") {
            Fact("Rank", "#${source.index}")
            Fact("Fused score", formatScore(source.score))
            evidence?.denseScore?.let { Fact("Dense similarity", formatScore(it)) }
            evidence?.keywordScore?.let { Fact("Keyword score", formatScore(it)) }
            evidence?.matchedTerms?.takeIf { it.isNotEmpty() }
                ?.let { Fact("Matched terms", it.joinToString(", ")) }
        }

        evidence?.memory?.let { memory ->
            Section("RECORD") {
                Fact("Record id", memory.memoryId)
                memory.subjectKey?.takeIf { it.isNotBlank() }?.let { Fact("Subject", it) }
                Fact("Version", memory.version.toString())
                Fact("Sync state", memory.syncState.name.lowercase())
                Fact("Origin", memory.origin.name.lowercase())
                if (memory.tags.isNotEmpty()) {
                    Fact("Tags", memory.tags.joinToString(", "))
                }
            }
        }
    }
}

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
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f),
        )
    }
}

/** Raw numeric score in technical notation — never a percentage/confidence. */
private fun formatScore(score: Double): String = "%.4f".format(score)
