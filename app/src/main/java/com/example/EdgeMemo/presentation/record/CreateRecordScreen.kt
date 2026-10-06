package com.example.EdgeMemo.presentation.record

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.domain.document.IngestionStage
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeDimens
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.EdgeLoadingState
import com.example.EdgeMemo.presentation.components.PillButton
import com.example.EdgeMemo.presentation.components.TonalPill
import com.example.EdgeMemo.presentation.memory.ChoiceRow
import com.example.EdgeMemo.presentation.memory.FieldLabel
import com.example.EdgeMemo.presentation.memory.PolicyLine
import com.example.EdgeMemo.presentation.memory.SquareChoiceChip
import com.example.EdgeMemo.presentation.memory.machineTag
import com.example.EdgeMemo.presentation.memory.typeLabel
import com.example.EdgeMemo.presentation.shell.LoadableState
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus
import com.example.EdgeMemo.ui.theme.EdgeType
import com.example.EdgeMemo.ui.theme.statusStyleFor

/** Record composer (pushed route). The shell draws Back; [onBack] stays for callers. */
@Composable
fun CreateRecordScreen(
    viewModel: CreateRecordViewModel,
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = viewModel::onDocumentPicked,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("edge-create-record-screen")
            .verticalScroll(rememberScrollState())
            .padding(horizontal = EdgeLayout.screenPadding)
            .padding(top = EdgeDimens.spacingXs, bottom = EdgeDimens.spacing2xl),
        verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingXl),
    ) {
        Text(
            text = if (state.subjectLocked) {
                "Add records to ${state.machineName ?: state.subject.uppercase()}"
            } else {
                "New record"
            },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        when (val dataState = state.data) {
            is LoadableState.Ready<Memory?> -> {
                val created = dataState.value
                if (created != null) {
                    CreatedConfirmation(
                        memory = created,
                        isDocument = state.type == MemoryType.DOCUMENT && state.ingestedChunkCount != null,
                        chunkCount = state.ingestedChunkCount,
                        onContinue = viewModel::onCreatedAcknowledged,
                        onAddAnother = viewModel::onAddAnother,
                    )
                } else {
                    Composer(
                        state = state,
                        viewModel = viewModel,
                        onPickDocument = { documentLauncher.launch(viewModel.supportedDocumentMimeTypes) },
                    )
                }
            }
            is LoadableState.Failed -> EdgeErrorState(dataState.message, onRetry = { /* no-op */ })
            is LoadableState.Loading -> EdgeLoadingState("Preparing") // should not occur
        }
    }
}

private val composerTypes = listOf(
    MemoryType.OBSERVATION,
    MemoryType.NOTE,
    MemoryType.REPAIR,
    MemoryType.EVENT,
    MemoryType.PROCEDURE,
    MemoryType.DOCUMENT,
)

@Composable
private fun Composer(
    state: CreateRecordUiState,
    viewModel: CreateRecordViewModel,
    onPickDocument: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("edge-create-record-composer"),
        verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingL),
    ) {
        if (state.subjectLocked) {
            // Machine capture: the subject is fixed to this machine's namespace,
            // so every initial record stays isolated. Shown read-only.
            Row(
                modifier = Modifier.fillMaxWidth().testTag("edge-create-subject-locked"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
            ) {
                Text("Machine", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.subject.uppercase(), style = EdgeType.numeric, color = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            FormField(
                label = "Machine or asset",
                value = state.subject,
                placeholder = "For example P-101",
                tag = "edge-create-subject",
                onValueChange = viewModel::onSubjectChange,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
            FieldLabel("Type")
            ChoiceRow {
                composerTypes.forEach { type ->
                    SquareChoiceChip(
                        label = typeLabel(type),
                        selected = state.type == type,
                        onClick = { viewModel.onTypeChange(type) },
                    )
                }
            }
        }

        if (state.type == MemoryType.DOCUMENT) {
            DocumentPicker(state, viewModel, onPickDocument)
        }

        FormField(
            label = "Title",
            value = state.title,
            placeholder = "What happened, in a few words",
            tag = "edge-create-title",
            onValueChange = viewModel::onTitleChange,
        )
        FormField(
            label = "Details",
            value = state.content,
            placeholder = "Readings, steps taken, parts used",
            tag = "edge-create-content",
            singleLine = false,
            onValueChange = viewModel::onContentChange,
        )
        FormField(
            label = "Tags (optional)",
            value = state.tags,
            placeholder = "seal, vibration, bearing",
            supporting = "Separate tags with commas.",
            tag = "edge-create-tags",
            onValueChange = viewModel::onTagsChange,
        )

        Column(verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
            FieldLabel("Sync")
            ChoiceRow {
                SquareChoiceChip("Let the app decide", state.syncChoice == null, { viewModel.onSyncChoiceChange(null) })
                SquareChoiceChip("Sync", state.syncChoice == SyncDecision.SYNC, { viewModel.onSyncChoiceChange(SyncDecision.SYNC) })
                SquareChoiceChip(
                    "Device only",
                    state.syncChoice == SyncDecision.LOCAL_ONLY,
                    { viewModel.onSyncChoiceChange(SyncDecision.LOCAL_ONLY) },
                )
            }
            Text(
                text = when (state.syncChoice) {
                    null -> "Checked when you save. Access codes and private notes stay on this device."
                    SyncDecision.SYNC -> "Syncs to your team when you're online."
                    SyncDecision.LOCAL_ONLY -> "Stays on this device and is never queued to sync."
                    SyncDecision.SYNC_REDACTED -> "Only a redacted copy syncs."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.error?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
            PillButton(
                text = if (state.isBusy) "Saving" else "Save record",
                onClick = viewModel::submit,
                enabled = !state.isBusy && state.isFormValid,
                modifier = Modifier.testTag("edge-create-submit"),
            )
            TonalPill(text = "Cancel", onClick = viewModel::cancel)
        }
    }
}

@Composable
private fun DocumentPicker(
    state: CreateRecordUiState,
    viewModel: CreateRecordViewModel,
    onPickDocument: () -> Unit,
) {
    // DOCUMENT type: real system file picker (SAF) for the types the
    // ingestion pipeline can extract (PDF / TXT / MD).
    Column(verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
        FieldLabel("File")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
            TonalPill(
                text = if (state.documentUri != null) "Change file" else "Choose file",
                onClick = onPickDocument,
                enabled = !state.isBusy,
                modifier = Modifier.testTag("edge-create-select-document"),
            )
            if (state.documentUri != null && !state.isBusy) {
                TextButton(onClick = viewModel::onDocumentClear) { Text("Remove") }
            }
        }
        Text(
            text = state.documentName ?: if (state.documentUri != null) "File selected" else "PDF, text or Markdown",
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.documentName != null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("edge-create-document-name"),
        )
        state.ingestionStage?.let { stage ->
            if (stage != IngestionStage.IDLE && stage != IngestionStage.COMPLETED) {
                Text(
                    text = when (stage) {
                        IngestionStage.SELECTING -> "Opening file"
                        IngestionStage.EXTRACTING -> "Reading text"
                        IngestionStage.CHUNKING -> "Splitting into sections"
                        IngestionStage.EMBEDDING -> "Indexing for search"
                        IngestionStage.STORING -> "Saving on this device"
                        else -> "Import failed"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stage == IngestionStage.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.testTag("edge-create-ingestion-stage"),
                )
            }
        }
    }
}

@Composable
private fun CreatedConfirmation(
    memory: Memory,
    isDocument: Boolean,
    chunkCount: Int?,
    onContinue: () -> Unit,
    onAddAnother: () -> Unit,
) {
    val tag = machineTag(memory.subjectKey)
    EdgeCard(modifier = Modifier.fillMaxWidth().testTag("edge-create-record-created")) {
        Column(verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
            Text("Record saved", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = memory.title.ifBlank { "Untitled record" },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                tag?.let { Text(it, style = EdgeType.numeric, color = MaterialTheme.colorScheme.onSurface) }
                Text(typeLabel(memory.type), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // The policy line already says "stays on this device"; the sync
            // state adds information only once the record can actually sync.
            val reason = memory.policyReason
            if (reason == null || memory.syncState != MemorySyncState.LOCAL) SyncStateLine(memory.syncState)
            reason?.let { PolicyLine(memory.syncDecision, it) }
            if (isDocument && chunkCount != null) {
                Text(
                    text = "Saved as $chunkCount searchable ${if (chunkCount == 1) "section" else "sections"}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("edge-create-document-summary"),
                )
            }
            Row(
                modifier = Modifier.padding(top = EdgeDimens.spacingS),
                horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
            ) {
                PillButton(
                    text = tag?.let { "Open $it" } ?: "Done",
                    onClick = onContinue,
                    modifier = Modifier.testTag("edge-create-open-asset"),
                )
                TonalPill(
                    text = "Add another",
                    onClick = onAddAnother,
                    modifier = Modifier.testTag("edge-create-add-another"),
                )
            }
        }
    }
}

@Composable
private fun SyncStateLine(syncState: MemorySyncState) {
    val (status, label) = when (syncState) {
        MemorySyncState.PENDING -> EdgeStatus.WARNING to "Queued to sync"
        MemorySyncState.FAILED -> EdgeStatus.CRITICAL to "Sync failed, will retry"
        MemorySyncState.SYNCED -> EdgeStatus.SYNCED to "Synced"
        MemorySyncState.LOCAL -> EdgeStatus.NEUTRAL to "Only on this device"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
        Box(Modifier.size(EdgeLayout.statusDotSize).background(statusStyleFor(status).color, CircleShape))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    placeholder: String,
    tag: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 4,
        maxLines = if (singleLine) 1 else 8,
        keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        shape = RoundedCornerShape(EdgeDimens.inputRadius),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
