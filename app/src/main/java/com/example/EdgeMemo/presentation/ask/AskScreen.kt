package com.example.EdgeMemo.presentation.ask

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.rag.CloudEscalation
import com.example.EdgeMemo.core.rag.SourceReference
import com.example.EdgeMemo.presentation.components.BackIcon
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.EdgeListGroup
import com.example.EdgeMemo.presentation.components.MarkdownBody
import com.example.EdgeMemo.presentation.components.PillButton
import com.example.EdgeMemo.presentation.components.SectionHeader
import com.example.EdgeMemo.presentation.components.StatusChip
import com.example.EdgeMemo.presentation.components.TonalPill
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus
import com.example.EdgeMemo.ui.theme.EdgeType
import com.example.EdgeMemo.ui.theme.statusStyleFor

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
 * grounded answer, and the numbered sources it came from. The input sits at
 * the bottom where a thumb reaches it; everything above is the result. Every
 * string shown about the answer comes from the real RAG response; nothing is
 * rewritten or invented by the UI layer.
 */
@Composable
fun AskScreen(
    viewModel: AskViewModel,
    onOpenCitation: (Int) -> Unit,
    onClearAssetContext: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val idle = state.phase == AskPhase.IDLE
    val scroll = rememberScrollState()
    val openCitation: (Int) -> Unit = { index ->
        if (state.sources.any { it.index == index }) onOpenCitation(index)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(AskUiTags.SCREEN)
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = EdgeLayout.screenPadding)
                .padding(top = 4.dp, bottom = 16.dp),
            // Idle: intro at the top, suggestions just above the input, close to the thumb.
            verticalArrangement = if (idle) {
                Arrangement.SpaceBetween
            } else {
                Arrangement.spacedBy(EdgeLayout.cardGap)
            },
        ) {
            when (state.phase) {
                AskPhase.IDLE -> {
                    IdleIntro()
                    Column(
                        modifier = Modifier.padding(top = EdgeLayout.sectionGap),
                        verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
                    ) {
                        Suggestions(onPick = viewModel::onQuestionChange)
                    }
                }

                AskPhase.RETRIEVING, AskPhase.GENERATING, AskPhase.ESCALATING -> {
                    QuestionHeading(state)
                    LoadingRow(state.phase)
                }

                AskPhase.ERROR -> {
                    QuestionHeading(state)
                    EdgeErrorState(
                        message = state.errorMessage ?: "The question couldn't be answered.",
                        onRetry = viewModel::retry,
                        modifier = Modifier.testTag(AskUiTags.ERROR),
                    )
                    NewQuestionButton(viewModel::clear)
                }

                AskPhase.SUCCESS, AskPhase.INSUFFICIENT -> {
                    QuestionHeading(state)
                    ProvenanceRow(state)
                    if (state.phase == AskPhase.SUCCESS) {
                        AnswerPanel(state, openCitation)
                    } else {
                        InsufficientPanel(state)
                    }
                    CloudSaveBlock(state, viewModel::saveToMemory)
                    SourcesSection(state, onOpenCitation)
                    Spacer(Modifier.height(6.dp))
                    NewQuestionButton(viewModel::clear)
                }
            }
        }

        ComposerBar(
            value = state.question,
            busy = state.isBusy,
            assetNamespace = state.assetNamespace,
            showNote = idle,
            contentBehind = scroll.canScrollForward,
            onValueChange = viewModel::onQuestionChange,
            onSubmit = viewModel::ask,
            onClearAsset = onClearAssetContext,
        )
    }
}

// ── Composer ───────────────────────────────────────────────────────────────

@Composable
private fun ComposerBar(
    value: String,
    busy: Boolean,
    assetNamespace: String?,
    showNote: Boolean,
    contentBehind: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClearAsset: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val canSend = !busy && value.isNotBlank()
    // Hairline only while the result continues underneath the input.
    if (contentBehind) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(scheme.outlineVariant),
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AskUiTags.COMPOSER)
            .background(scheme.background)
            .padding(horizontal = EdgeLayout.screenPadding)
            .padding(top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (assetNamespace != null) {
            AssetContextRow(assetNamespace, onClearAsset)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .background(scheme.surface, RoundedCornerShape(16.dp))
                .border(1.dp, scheme.outline, RoundedCornerShape(16.dp))
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = value,
                onValueChange = { if (!busy) onValueChange(it) },
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 12.dp, bottom = 12.dp, end = 10.dp)
                    .testTag(AskUiTags.COMPOSER_INPUT)
                    .semantics { contentDescription = "Question" },
                textStyle = EdgeType.bodyEmphasis.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSubmit() }),
                maxLines = 5,
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = if (assetNamespace != null) {
                                    "Ask about ${assetNamespace.uppercase()}"
                                } else {
                                    "Ask about a machine or a past repair"
                                },
                                style = EdgeType.bodyEmphasis,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            Surface(
                onClick = onSubmit,
                enabled = canSend,
                shape = RoundedCornerShape(12.dp),
                color = if (canSend) scheme.primary else scheme.surfaceVariant,
                contentColor = if (canSend) scheme.onPrimary else scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(EdgeLayout.minTarget)
                    .testTag(AskUiTags.SUBMIT)
                    .semantics { contentDescription = if (busy) "Working" else "Ask" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = scheme.onSurfaceVariant,
                        )
                    } else {
                        Icon(SendIcon, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        if (showNote) {
            Text(
                text = "Answers come only from records on this device, with sources.",
                style = EdgeType.metadata,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun AssetContextRow(assetNamespace: String, onClear: () -> Unit) {
    val tag = assetNamespace.uppercase()
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusChip(
            text = "Asking about $tag",
            color = MaterialTheme.colorScheme.onSurface,
            showDot = false,
            modifier = Modifier
                .testTag(AskUiTags.ASSET_CHIP)
                .semantics {
                    contentDescription =
                        "Asking about $tag. The tag is added to your question; other records can still match."
                },
        )
        Box(
            modifier = Modifier
                .heightIn(min = EdgeLayout.minTarget)
                .clickable(onClick = onClear)
                .semantics { contentDescription = "Stop asking about $tag" }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Clear", style = EdgeType.label, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ── Idle ───────────────────────────────────────────────────────────────────

@Composable
private fun IdleIntro() {
    Column(
        modifier = Modifier.padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Search your maintenance history",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Ask the way you'd ask whoever had the last shift. " +
                "It works without signal.",
            style = EdgeType.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Suggestions(onPick: (String) -> Unit) {
    SectionHeader(title = "Try asking")
    EdgeListGroup(items = SUGGESTIONS) { suggestion ->
        Text(
            text = suggestion,
            style = EdgeType.body,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = EdgeLayout.minTarget)
                .testTag("edge-ask-suggestion")
                .semantics { contentDescription = "$suggestion. Puts it in the question box." }
                .clickable { onPick(suggestion) }
                .padding(horizontal = 16.dp, vertical = 13.dp),
        )
    }
}

// ── Result ─────────────────────────────────────────────────────────────────

@Composable
private fun QuestionHeading(state: AskUiState) {
    val submitted = state.submittedQuestion ?: return
    Column(
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = submitted,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 27.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        val executed = state.executedQuestion
        if (state.assetNamespace != null && executed != null && executed != submitted) {
            Text(
                text = "Searched as “$executed”",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingRow(phase: AskPhase) {
    val label = when (phase) {
        AskPhase.RETRIEVING -> "Searching records on this device"
        AskPhase.GENERATING -> "Reading the matching records"
        AskPhase.ESCALATING -> "Not enough here, asking the cloud"
        else -> "Working"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AskUiTags.LOADING)
            .semantics { contentDescription = label }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(label, style = EdgeType.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Where the answer came from, from the real response state only. */
@Composable
private fun ProvenanceRow(state: AskUiState) {
    val (status, label) = when {
        state.provenance == AskProvenance.CLOUD -> EdgeStatus.SYNCING to "Cloud answer"
        state.phase == AskPhase.SUCCESS -> EdgeStatus.HEALTHY to "From your records"
        else -> EdgeStatus.NEUTRAL to "Searched this device"
    }
    val style = statusStyleFor(status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AskUiTags.PROVENANCE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusChip(text = label, color = style.color, containerColor = style.container)
        if (state.phase == AskPhase.SUCCESS && !state.isCloudAnswer) {
            val n = state.sources.size
            Text(
                text = "Based on $n source${if (n == 1) "" else "s"}",
                style = EdgeType.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AnswerPanel(state: AskUiState, onCitation: (Int) -> Unit) {
    if (state.answer.isBlank()) return
    EdgeCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AskUiTags.ANSWER),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        MarkdownBody(state.answer, onCitationClick = onCitation)
        if (state.escalation is CloudEscalation.Answered) {
            Spacer(Modifier.height(12.dp))
            Note("This came from the cloud, not your records. It's only kept if you save it.")
        }
    }
}

/** Insufficient evidence: amber, plain words, and what to try next. */
@Composable
private fun InsufficientPanel(state: AskUiState) {
    val warn = statusStyleFor(EdgeStatus.WARNING)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AskUiTags.INSUFFICIENT),
        shape = RoundedCornerShape(14.dp),
        color = warn.container,
        border = BorderStroke(1.dp, warn.color.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .padding(top = 7.dp)
                        .size(8.dp)
                        .background(warn.color, CircleShape),
                )
                Text(
                    text = "Not enough in your records to answer this.",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = "Try adding the machine tag or the part name. If it was never logged, " +
                    "add a record from the machine's page.",
                style = EdgeType.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp),
            )
            when (state.escalation) {
                CloudEscalation.Offline -> Note("You're offline, so the cloud wasn't asked.", Modifier.padding(start = 18.dp))
                CloudEscalation.Unavailable -> Note("The cloud couldn't answer either.", Modifier.padding(start = 18.dp))
                else -> Unit
            }
        }
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = EdgeType.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun CloudSaveBlock(state: AskUiState, onSave: () -> Unit) {
    when {
        state.canSave -> PillButton(text = "Save to memory", onClick = onSave)
        state.cacheInFlight -> Note("Saving")
        state.savedToMemory -> StatusChip(
            text = "Saved to this device",
            color = statusStyleFor(EdgeStatus.SYNCED).color,
            containerColor = statusStyleFor(EdgeStatus.SYNCED).container,
        )
    }
    state.cacheMessage?.let { Note(it) }
}

@Composable
private fun SourcesSection(state: AskUiState, onOpenCitation: (Int) -> Unit) {
    if (state.sources.isEmpty()) return
    val insufficient = state.phase == AskPhase.INSUFFICIENT
    Column(
        modifier = Modifier
            .padding(top = EdgeLayout.sectionGap - EdgeLayout.cardGap)
            .testTag(AskUiTags.EVIDENCE_SECTION),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
    ) {
        SectionHeader(
            title = if (insufficient) "Closest records" else "Sources",
            subtitle = if (insufficient) "These came up in the search but don't answer it." else null,
        )
        EdgeListGroup(items = state.sources) { source ->
            val subject = state.evidence.firstOrNull { it.memory.memoryId == source.memoryId }
                ?.memory?.subjectKey
            SourceRow(source, subject, onOpen = { onOpenCitation(source.index) })
        }
    }
}

/** One numbered source; the number is the `[n]` used in the answer. */
@Composable
private fun SourceRow(source: SourceReference, subjectKey: String?, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val title = source.title.ifBlank { "Untitled record" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag("${AskUiTags.EVIDENCE_CARD_PREFIX}${source.index}")
            .semantics { contentDescription = "Source ${source.index}: $title. Open details." }
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CitationNumber(source.index)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                machineTagOf(subjectKey)?.let {
                    Text(it, style = EdgeType.numeric.copy(fontSize = 12.sp), color = scheme.onSurfaceVariant)
                }
                Text(typeLabel(source.type), style = EdgeType.label, color = scheme.onSurfaceVariant)
                Text(
                    "Score ${"%.3f".format(source.score)}",
                    style = EdgeType.label,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            BackIcon,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier
                .size(18.dp)
                .rotate(180f),
        )
    }
}

@Composable
internal fun CitationNumber(index: Int) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = index.toString(),
            style = EdgeType.numeric.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun NewQuestionButton(onNew: () -> Unit) {
    TonalPill(
        text = "New question",
        onClick = onNew,
        modifier = Modifier.testTag(AskUiTags.NEW_QUESTION),
    )
}

// ── Helpers ────────────────────────────────────────────────────────────────

/** Machine tag from a subject key's namespace (`p-101/seal` -> `P-101`). */
internal fun machineTagOf(subjectKey: String?): String? =
    subjectKey?.substringBefore('/')?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()

internal fun typeLabel(type: MemoryType): String =
    type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

private val SUGGESTIONS = listOf(
    "Why is this machine failing repeatedly?",
    "What maintenance was done recently?",
    "What failures or incidents are on record?",
    "Which procedures are available?",
    "What was observed on recent inspections?",
)

private val SendIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Send",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 19f)
            lineTo(12f, 5f)
            moveTo(6f, 11f)
            lineTo(12f, 5f)
            lineTo(18f, 11f)
        }
    }.build()
}
