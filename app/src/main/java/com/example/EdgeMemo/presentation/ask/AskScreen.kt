package com.example.EdgeMemo.presentation.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.core.rag.CloudEscalation
import com.example.EdgeMemo.core.rag.SourceReference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskScreen(viewModel: AskViewModel) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Ask")
                        Text(
                            "LOCAL MEMORY \u00b7 OFFLINE",
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ask your local memory", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.question,
                        onValueChange = viewModel::onQuestionChange,
                        label = { Text("Question") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = viewModel::ask,
                            enabled = !state.isBusy && state.question.isNotBlank(),
                        ) {
                            Text("Ask")
                        }
                        if (state.phase != AskPhase.IDLE) {
                            OutlinedButton(onClick = viewModel::clear, enabled = !state.isBusy) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }

            if (state.isBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(
                        when (state.phase) {
                            AskPhase.RETRIEVING -> "searching local memory\u2026"
                            AskPhase.GENERATING -> "composing grounded answer\u2026"
                            AskPhase.ESCALATING -> "asking cloud\u2026"
                            else -> "working\u2026"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            state.errorMessage?.let { message ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Error", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (state.hasResult) {
                AnswerCard(
                    state = state,
                    onToggleSource = viewModel::toggleSource,
                    onSave = viewModel::saveToMemory,
                )
            }
        }
    }
}

@Composable
private fun AnswerCard(
    state: AskUiState,
    onToggleSource: (Int) -> Unit,
    onSave: () -> Unit,
) {
    val escalation = state.escalation

    if (escalation is CloudEscalation.Answered) {
        CloudAnswerCard(state, onSave)
        return
    }

    if (escalation == CloudEscalation.Offline) {
        LimitationHint("OFFLINE \u2014 no cloud escalation available")
    }
    if (escalation == CloudEscalation.Unavailable) {
        LimitationHint("CLOUD ESCALATION UNAVAILABLE")
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (state.phase == AskPhase.INSUFFICIENT) "INSUFFICIENT EVIDENCE" else "GROUNDED ANSWER",
                style = MaterialTheme.typography.labelMedium,
                color = if (state.phase == AskPhase.INSUFFICIENT) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(state.answer, style = MaterialTheme.typography.bodyMedium)

            if (state.sources.isNotEmpty()) {
                Text(
                    "Sources (${state.sources.size})",
                    style = MaterialTheme.typography.titleSmall,
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
    }
}

@Composable
private fun CloudAnswerCard(state: AskUiState, onSave: () -> Unit) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "CLOUD ANSWER",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            val escalation = state.escalation as CloudEscalation.Answered
            Text(escalation.answer, style = MaterialTheme.typography.bodyMedium)
            Text(
                "returned by cloud${escalation.authority?.let { " \u00b7 $it" } ?: ""} \u00b7 not verified locally",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.canSave) {
                Button(onClick = onSave) {
                    Text("Save to memory")
                }
            } else if (state.cacheInFlight) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp))
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("saving\u2026", style = MaterialTheme.typography.labelSmall)
                }
            } else if (state.savedToMemory) {
                Text(
                    "SAVED TO LOCAL MEMORY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            state.cacheMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.savedToMemory) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
            }
        }
    }
}

@Composable
private fun LimitationHint(text: String) {
    Card {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun SourceCard(source: SourceReference, expanded: Boolean, onToggle: () -> Unit) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "[${source.index}]",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(source.title, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                sourceLocation(source),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Text(source.snippet, style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedButton(onClick = onToggle) { Text("Inspect evidence") }
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
}.joinToString(" \u00b7 ")