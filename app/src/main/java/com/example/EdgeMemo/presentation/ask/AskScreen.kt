package com.example.EdgeMemo.presentation.ask

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.core.rag.CloudEscalation
import com.example.EdgeMemo.core.rag.SourceReference
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeCardSecondary
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.MarkdownBody
import com.example.EdgeMemo.presentation.components.PillButton
import com.example.EdgeMemo.presentation.components.StatusChip
import com.example.EdgeMemo.presentation.components.TechLabel
import com.example.EdgeMemo.presentation.components.TonalPill
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus
import com.example.EdgeMemo.ui.theme.statusColor
import com.example.EdgeMemo.ui.theme.EdgeType

/** Stable tags for UI Phase 2 tests. */
object AskUiTags {
    const val SCREEN = "edge-ask-screen"
    const val COMPOSER = "edge-ask-composer"
    const val COMPOSER_INPUT = "edge-ask-composer-input"
    const val SUBMIT = "edge-ask-submit"
    const val LOADING = "edge-ask-loading"
    const val ANSWER = "edge-ask-answer"
    const val INSUFFICIENT = "edge-ask-insufficient"
    const val ERROR = "edge-ask-error"
    const val RETRY = "edge-ask-retry"
    const val NEW_QUESTION = "edge-ask-new"
    const val EVIDENCE_SECTION = "edge-ask-evidence"
    const val EVIDENCE_CARD_PREFIX = "edge-evidence-card-"
    const val ASSET_CHIP = "edge-ask-asset-chip"
    const val PROVENANCE = "edge-ask-provenance"
}

/**
 * The grounded Ask console. Deliberately NOT a chatbot: one question, the
 * grounded answer, and the evidence that produced it — in an explicit
 * QUESTION → RETRIEVAL → EVIDENCE → ANSWER → SOURCE structure. Every string
 * shown here comes from the real RAG response; nothing is rewritten or
 * invented by the UI layer.
 */
@Composable
fun AskScreen(
    viewModel: AskViewModel,
    onOpenCitation: (Int) -> Unit,
    onClearAssetContext: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(AskUiTags.SCREEN)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = EdgeLayout.screenPadding, vertical = EdgeLayout.cardGap),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
    ) {
        AskHeading(assetNamespace = state.assetNamespace, onClearAsset = onClearAssetContext)

        Composer(
            value = state.question,
            busy = state.isBusy,
            onValueChange = viewModel::onQuestionChange,
            onSubmit = viewModel::ask,
            focusRequester = focusRequester,
        )

        when (state.phase) {
            AskPhase.IDLE -> IdleGuide(
                onPickSuggestion = { suggestion ->
                    viewModel.onQuestionChange(suggestion)
                },
            )

            AskPhase.RETRIEVING, AskPhase.GENERATING, AskPhase.ESCALATING -> LoadingPanel(state)

            AskPhase.ERROR -> {
                SubmittedQuestionRow(state)
                EdgeErrorState(
                    message = state.errorMessage ?: "The question could not be answered.",
                    onRetry = viewModel::retry,
                    modifier = Modifier.testTag(AskUiTags.ERROR),
                )
                NewQuestionRow(viewModel::clear)
            }

            AskPhase.SUCCESS, AskPhase.INSUFFICIENT -> {
                SubmittedQuestionRow(state)
                if (state.isBusy.not()) {
                    ProvenanceRow(state)
                }
                AnswerBlock(state)
                EscalationNote(state)
                CloudSaveBlock(state, viewModel::saveToMemory)
                EvidenceSection(state, onOpenCitation)
                NewQuestionRow(viewModel::clear)
            }
        }
    }
}

@Composable
private fun AskHeading(assetNamespace: String?, onClearAsset: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap)) {
        TechLabel(text = "Ask EdgeMind")
        Text(
            text = "Grounded answers from your local maintenance memory — offline first.",
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (assetNamespace != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
            ) {
                StatusChip(
                    text = "ASSET CONTEXT · ${assetNamespace.uppercase()}",
                    color = MaterialTheme.colorScheme.primary,
                    showDot = true,
                    modifier = Modifier
                        .testTag(AskUiTags.ASSET_CHIP)
                        .semantics {
                            contentDescription =
                                "Asset context $assetNamespace: the asset reference is included " +
                                    "in the executed question; results are not hard-filtered"
                        },
                )
                Text(
                    text = "clear",
                    style = EdgeType.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(onClick = onClearAsset)
                        .padding(EdgeLayout.compactGap),
                )
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
) {
    EdgeCard(modifier = Modifier.testTag(AskUiTags.COMPOSER)) {
        Text(
            text = "QUESTION",
            style = EdgeType.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(EdgeLayout.compactGap))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp, max = 168.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    RoundedCornerShape(12.dp),
                )
                .padding(EdgeLayout.cardGap),
        ) {
            BasicTextField(
                value = value,
                onValueChange = { if (!busy) onValueChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AskUiTags.COMPOSER_INPUT)
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = "Question input" },
                textStyle = EdgeType.bodyEmphasis.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!busy && value.isNotBlank()) onSubmit() }),
                maxLines = 6,
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = "Ask about maintenance history, failures, procedures…",
                            style = EdgeType.body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    inner()
                },
            )
        }
        Spacer(Modifier.size(EdgeLayout.cardGap))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
        ) {
            PillButton(
                text = if (busy) "Working…" else "Ask",
                onClick = onSubmit,
                enabled = !busy && value.isNotBlank(),
                modifier = Modifier.testTag(AskUiTags.SUBMIT),
            )
            if (value.isNotEmpty() && !busy) {
                TonalPill(text = "Clear", onClick = { onValueChange("") })
            }
            Spacer(Modifier.weight(1f))
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun IdleGuide(onPickSuggestion: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        EdgeCardSecondary {
            Text(
                text = "EdgeMind answers from retrieved local records only. Every answer " +
                    "lists the evidence it was grounded on; when evidence is insufficient " +
                    "it says so instead of guessing.",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TechLabel(text = "SUGGESTED QUESTIONS", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SUGGESTIONS.forEach { suggestion ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(EdgeLayout.hairline, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("edge-ask-suggestion")
                    .semantics { contentDescription = "Suggestion: $suggestion — fills the question box" }
                    .clickable { onPickSuggestion(suggestion) },
            ) {
                Text(
                    text = "› $suggestion",
                    style = EdgeType.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = EdgeLayout.cardGap, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun LoadingPanel(state: AskUiState) {
    val label = when (state.phase) {
        AskPhase.RETRIEVING -> "Searching local knowledge…"
        AskPhase.GENERATING -> "Evaluating evidence…"
        AskPhase.ESCALATING -> "Local evidence insufficient — asking cloud…"
        else -> "Working…"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AskUiTags.LOADING)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = label,
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SubmittedQuestionRow(state: AskUiState) {
    val submitted = state.submittedQuestion ?: return
    Column {
        Text(
            text = "QUESTION",
            style = EdgeType.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = submitted,
            style = EdgeType.bodyEmphasis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val executed = state.executedQuestion
        if (state.assetNamespace != null && executed != null && executed != submitted) {
            Text(
                text = "executed query: $executed",
                style = EdgeType.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ProvenanceRow(state: AskUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AskUiTags.PROVENANCE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
    ) {
        when (state.provenance) {
            AskProvenance.CLOUD -> StatusChip(
                text = "CLOUD ESCALATION",
                color = MaterialTheme.colorScheme.primary,
            )
            AskProvenance.LOCAL -> StatusChip(
                text = if (state.phase == AskPhase.INSUFFICIENT) "LOCAL · INSUFFICIENT" else "LOCAL KNOWLEDGE",
                color = when {
                    state.phase == AskPhase.INSUFFICIENT -> EdgeStatus.WARNING.statusColor()
                    else -> EdgeStatus.HEALTHY.statusColor()
                },
            )
            AskProvenance.NONE -> Unit
        }
        if (state.phase == AskPhase.SUCCESS && !state.isCloudAnswer) {
            Text(
                text = "Grounded in ${state.sources.size} source${if (state.sources.size == 1) "" else "s"}",
                style = EdgeType.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AnswerBlock(state: AskUiState) {
    if (state.answer.isBlank()) return
    when (state.phase) {
        AskPhase.SUCCESS -> EdgeCard(modifier = Modifier.testTag(AskUiTags.ANSWER)) {
            Text("ANSWER", style = EdgeType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(EdgeLayout.compactGap))
            MarkdownBody(state.answer)
        }
        AskPhase.INSUFFICIENT -> EdgeCard(
            modifier = Modifier.testTag(AskUiTags.INSUFFICIENT),
        ) {
            TechLabel(text = "NOT ENOUGH EVIDENCE", color = EdgeStatus.WARNING.statusColor())
            Spacer(Modifier.size(EdgeLayout.compactGap))
            Text(
                text = "The stored knowledge does not sufficiently support this question — " +
                    "EdgeMind will not guess.",
                style = EdgeType.body,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(EdgeLayout.compactGap))
            if (state.answer.isNotBlank()) {
                Text(
                    text = state.answer,
                    style = EdgeType.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> Unit
    }
}

@Composable
private fun EscalationNote(state: AskUiState) {
    when (state.escalation) {
        CloudEscalation.Offline -> EscalationLine(
            "Offline — local evidence was insufficient and cloud escalation is unavailable. " +
                "Local knowledge is unaffected.",
        )
        CloudEscalation.Unavailable -> EscalationLine(
            "Online, but the cloud could not answer. Only the local result above is real.",
        )
        is CloudEscalation.Answered -> EscalationLine(
            "Cloud answer shown with provenance — it is not local evidence and was not " +
                "stored automatically.",
        )
        null -> Unit
    }
}

@Composable
private fun EscalationLine(text: String) {
    Text(
        text = text,
        style = EdgeType.metadata,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CloudSaveBlock(state: AskUiState, onSave: () -> Unit) {
    when {
        state.canSave -> PillButton(text = "Save to memory", onClick = onSave)
        state.cacheInFlight -> EscalationLine("Saving…")
        state.savedToMemory -> StatusChip(
            text = "SAVED TO LOCAL MEMORY",
            color = EdgeStatus.SYNCED.statusColor(),
        )
    }
    state.cacheMessage?.let { EscalationLine(it) }
}

@Composable
private fun EvidenceSection(state: AskUiState, onOpenCitation: (Int) -> Unit) {
    if (state.sources.isEmpty()) return
    Column(
        modifier = Modifier.testTag(AskUiTags.EVIDENCE_SECTION),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
    ) {
        TechLabel(
            text = "EVIDENCE · ${state.sources.size} SOURCE${if (state.sources.size == 1) "" else "S"}",
            color = MaterialTheme.colorScheme.primary,
        )
        state.sources.forEach { source ->
            EvidenceCard(source = source, onOpen = { onOpenCitation(source.index) })
        }
    }
}

/**
 * Compact technical citation card: real fields only, empty fields omitted.
 * Opens the full traceability detail on tap.
 */
@Composable
private fun EvidenceCard(source: SourceReference, onOpen: () -> Unit) {
    EdgeCardSecondary(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${AskUiTags.EVIDENCE_CARD_PREFIX}${source.index}")
            .semantics {
                contentDescription = "Evidence ${source.index}: ${source.title}. " +
                    "Open citation detail."
            }
            .clickable(onClick = onOpen),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = "[${source.index}]",
                style = EdgeType.numeric,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(EdgeLayout.compactGap))
            Text(
                text = source.title.ifBlank { "Untitled record" },
                style = EdgeType.bodyEmphasis,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "›",
                style = EdgeType.bodyEmphasis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = citationLocation(source),
            style = EdgeType.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (source.snippet.isNotBlank()) {
            Text(
                text = "\u201C${source.snippet}\u201D",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NewQuestionRow(onNew: () -> Unit) {
    TonalPill(
        text = "New question",
        onClick = onNew,
        modifier = Modifier.testTag(AskUiTags.NEW_QUESTION),
    )
}

private fun citationLocation(source: SourceReference): String = buildList {
    add(source.type.name.lowercase().replace('_', ' '))
    add("source ${source.source}")
    source.page?.let { add("page $it") }
    source.section?.let { add(it) }
    source.chunkIndex?.let { add("chunk ${it + 1}") }
    source.chunkId?.let { add("chunk-id $it") }
}.joinToString(" · ")

private val SUGGESTIONS = listOf(
    "Why is this asset failing repeatedly?",
    "What maintenance has this asset had recently?",
    "What failures or incidents are recorded?",
    "What procedures are available?",
    "What observations were recently recorded?",
    "What evidence exists for the repeated failure?",
)
