package com.example.EdgeMemo.presentation.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.EdgeMemo.core.rag.CloudEscalation
import com.example.EdgeMemo.core.rag.SourceReference
import com.example.EdgeMemo.core.retrieval.EvidenceItem
import com.example.EdgeMemo.presentation.components.AuroraBackground
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeCardSecondary
import com.example.EdgeMemo.presentation.components.EdgeDimens
import com.example.EdgeMemo.presentation.components.IconPill
import com.example.EdgeMemo.presentation.components.MarkdownBody
import com.example.EdgeMemo.presentation.components.PillButton
import com.example.EdgeMemo.presentation.components.StatusChip
import com.example.EdgeMemo.presentation.components.TechLabel
import com.example.EdgeMemo.presentation.components.greetingFor
import com.example.EdgeMemo.ui.theme.LocalEdgeColors
import java.util.Calendar

@Composable
fun AskScreen(viewModel: AskViewModel, profileName: String) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var hasFocused by remember { mutableStateOf(false) }

    // Auto-focus the input and show keyboard when Ask screen becomes visible
    // and there's no conversation yet (fresh session)
    LaunchedEffect(state.hasConversation) {
        if (!state.hasConversation && !hasFocused) {
            focusRequester.requestFocus()
            keyboard?.show()
            hasFocused = true
        }
    }

    // Reset focus state when conversation is cleared
    LaunchedEffect(state.hasConversation) {
        if (!state.hasConversation) {
            hasFocused = false
        }
    }

    // Scroll to bottom when new content arrives
    LaunchedEffect(state.submittedQuestion, state.phase, state.answer, state.errorMessage) {
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (state.hasConversation && lastIndex >= 0) {
            listState.animateScrollToItem(lastIndex)
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = EdgeDimens.spacingL,
                end = EdgeDimens.spacingL,
                top = EdgeDimens.spacingS,
                bottom = EdgeDimens.spacingXl,
            ),
            verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingM),
        ) {
            if (!state.hasConversation) {
                item { Greeting(profileName) }
            }

            state.submittedQuestion?.let { submitted ->
                item(key = "question") { QuestionBubble(submitted) }
            }

            if (state.isBusy) {
                item(key = "busy") { BusyIndicator(state.phase) }
            }

            state.errorMessage?.let { message ->
                item(key = "error") { ErrorCard(message) }
            }

            if (state.hasResult) {
                item(key = "answer") {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(240)) +
                            slideInVertically(
                                animationSpec = androidx.compose.animation.core.tween(240),
                                initialOffsetY = { it / 8 },
                            ),
                    ) {
                        AnswerPanel(
                            state = state,
                            onToggleSource = viewModel::toggleSource,
                            onSave = viewModel::saveToMemory,
                            onReset = viewModel::clear,
                        )
                    }
                }
            }
        }

        AskInputBar(
            question = state.question,
            busy = state.isBusy,
            onQuestionChange = viewModel::onQuestionChange,
            onAsk = viewModel::ask,
            focusRequester = focusRequester,
        )
    }
}

@Composable
private fun Greeting(profileName: String) {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = EdgeDimens.spacingXl, bottom = EdgeDimens.spacingL),
        verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = greetingFor(hour, profileName),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Ask your local memory — offline first.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuestionBubble(question: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 8.dp,
            ),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
            border = androidx.compose.foundation.BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            ),
        ) {
            Text(
                text = question,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .padding(horizontal = EdgeDimens.spacingL, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun BusyIndicator(phase: AskPhase) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingM),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = when (phase) {
                AskPhase.RETRIEVING -> "Searching your memory…"
                AskPhase.GENERATING -> "Composing a grounded answer…"
                AskPhase.ESCALATING -> "Asking the cloud…"
                else -> "Working…"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    EdgeCard {
        Text(
            text = "ASK FAILED",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(EdgeDimens.spacingXs))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun AnswerPanel(
    state: AskUiState,
    onToggleSource: (Int) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    val escalation = state.escalation

    Column(verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingM)) {
        // Provenance chip - subtle, not a full card wrapper
        ProvenanceChip(state)

        // Answer content directly on background - NO EdgeCard wrapper
        if (state.isCloudAnswer) {
            val answered = escalation as CloudEscalation.Answered
            MarkdownBody(answered.answer)
        } else if (state.answer.isNotBlank()) {
            MarkdownBody(state.answer)
        }

        // Escalation hint
        EscalationHint(state)

        // Cloud save action
        CloudSaveAction(state, onSave)

        // Sources - lightweight, visually subordinate
        if (!state.isCloudAnswer && state.sources.isNotEmpty()) {
            SourcesPanel(state, onToggleSource)
        }

        // New question action - subtle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(onClick = onReset) {
                Text(
                    text = "New question",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ProvenanceChip(state: AskUiState) {
    val edgeColors = LocalEdgeColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
        when {
            state.isCloudAnswer -> StatusChip(
                text = "Cloud answer · not verified locally",
                color = edgeColors.accentBlue,
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                showDot = false,
            )
            state.phase == AskPhase.INSUFFICIENT -> StatusChip(
                text = "Insufficient local evidence",
                color = edgeColors.accentAmber,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                showDot = false,
            )
            else -> StatusChip(
                text = "Answered from your memory",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                showDot = false,
            )
        }
    }
}

@Composable
private fun EscalationHint(state: AskUiState) {
    when (state.escalation) {
        CloudEscalation.Offline -> {
            Text(
                text = "OFFLINE — no cloud escalation available. This question stays queued in your history until connectivity returns.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        CloudEscalation.Unavailable -> {
            Text(
                text = "CLOUD ESCALATION UNAVAILABLE — the cloud could not answer right now.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        else -> Unit
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CloudSaveAction(
    state: AskUiState,
    onSave: () -> Unit,
) {
    when {
        state.canSave -> PillButton(
            text = "Save to memory",
            onClick = onSave,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        state.cacheInFlight -> Row(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                "Saving…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.savedToMemory -> StatusChip(
            text = "Saved to local memory",
            modifier = Modifier.align(Alignment.CenterHorizontally),
            color = LocalEdgeColors.current.positive,
            containerColor = LocalEdgeColors.current.positiveContainer.copy(alpha = 0.7f),
            showDot = false,
        )
    }
    state.cacheMessage?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.savedToMemory) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.tertiary
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun SourcesPanel(state: AskUiState, onToggleSource: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
        Text(
            text = "Sources (${state.sources.size})",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp),
        )
        state.sources.forEach { source ->
            SourceCard(
                source = source,
                expanded = state.expandedSourceIndex == source.index,
                onToggle = { onToggleSource(source.index) },
            )
        }
    }
}

@Composable
private fun SourceCard(source: SourceReference, expanded: Boolean, onToggle: () -> Unit) {
    // Lightweight source card - secondary surface, no heavy border
    EdgeCardSecondary(
        contentPadding = PaddingValues(EdgeDimens.spacingM),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
                Text(
                    text = "[${source.index}]",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = source.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = sourceLocation(source),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Text(
                    text = source.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TextButton(onClick = onToggle, modifier = Modifier.align(Alignment.End)) {
                    Text("Inspect evidence", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun AskInputBar(
    question: String,
    busy: Boolean,
    onQuestionChange: (String) -> Unit,
    onAsk: () -> Unit,
    focusRequester: FocusRequester,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var fieldValue by remember { mutableStateOf(TextFieldValue()) }
    var isFocused by remember { mutableStateOf(false) }

    // Sync fieldValue with question from ViewModel
    LaunchedEffect(question) {
        if (fieldValue.text != question) {
            fieldValue = fieldValue.copy(
                text = question,
                selection = TextRange(question.length),
            )
        }
    }

    // Track focus state to control horizontal width
    val isEmpty = question.isEmpty()

    // Compact when empty AND not focused (after initial auto-focus has been used)
    val shouldBeCompact = isEmpty && !isFocused

    // Animate width fraction: 80% when compact, 100% when focused/typing
    val animatedWidthFraction by animateFloatAsState(
        targetValue = if (shouldBeCompact) 0.8f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Centered container that animates width
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(animatedWidthFraction)
                    .padding(horizontal = EdgeDimens.spacingM, vertical = EdgeDimens.spacingS)
                    .height(56.dp),
                shape = RoundedCornerShape(EdgeDimens.inputRadius),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    0.5.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f),
                ),
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = EdgeDimens.spacingL, end = EdgeDimens.spacingXs)
                        .height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 12.dp)
                            .fillMaxSize()
                            .onFocusChanged { focusState ->
                                isFocused = focusState.isFocused
                            },
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = fieldValue,
                            onValueChange = {
                                fieldValue = it
                                onQuestionChange(it.text)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Send,
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                            ),
                            keyboardActions = KeyboardActions(onSend = {
                                if (!busy && question.isNotBlank()) {
                                    keyboard?.hide()
                                    onAsk()
                                }
                            }),
                            maxLines = 6,
                            decorationBox = { innerTextField ->
                                Box(Modifier.fillMaxSize()) {
                                    if (question.isEmpty()) {
                                        Text(
                                            text = "Ask EdgeMind…",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 4.dp)
                                                .align(Alignment.CenterStart),
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                    }
                    Spacer(Modifier.size(EdgeDimens.spacingXs))
                    IconPill(
                        icon = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Ask",
                        onClick = {
                            if (!busy && question.isNotBlank()) {
                                keyboard?.hide()
                                onAsk()
                            }
                        },
                        enabled = !busy && question.isNotBlank(),
                    )
                }
            }
        }
    }
}

private fun sourceLocation(source: SourceReference): String = buildList {
    add("source ${source.source}")
    source.page?.let { add("page $it") }
    source.section?.let { add(it) }
    source.chunkIndex?.let { add("chunk ${it + 1}") }
    add("memory ${source.memoryId.take(8)}")
}.joinToString(" · ")