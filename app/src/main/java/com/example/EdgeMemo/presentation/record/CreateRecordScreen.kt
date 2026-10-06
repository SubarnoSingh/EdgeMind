package com.example.EdgeMemo.presentation.record

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeDimens
import com.example.EdgeMemo.presentation.components.EdgeEmptyState
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.EdgeLoadingState
import com.example.EdgeMemo.presentation.components.EdgeSpacer
import com.example.EdgeMemo.presentation.components.PillButton
import com.example.EdgeMemo.presentation.components.StatusChip
import com.example.EdgeMemo.presentation.components.TechLabel
import com.example.EdgeMemo.presentation.components.TonalPill
import com.example.EdgeMemo.presentation.shell.LoadableState
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll

@Composable
fun CreateRecordScreen(
    viewModel: CreateRecordViewModel,
    onBack: () -> Unit,
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
            .padding(horizontal = EdgeLayout.screenPadding, vertical = EdgeLayout.cardGap),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "\u2190 Back",
                style = EdgeType.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .semantics { contentDescription = "Back" }
                    .clickable(onClick = onBack)
                    .padding(EdgeLayout.compactGap),
            )
            Spacer(Modifier.weight(1f))
            TechLabel(
                text = if (state.subjectLocked) {
                    "ADD RECORDS · ${(state.machineName ?: state.subject).uppercase()}"
                } else {
                    "NEW RECORD"
                },
            )
        }

        when (val dataState = state.data) {
            is LoadableState.Ready<Memory?> -> {
                val created = dataState.value
                if (created != null) {
                    CreatedConfirmationCard(
                        memory = created,
                        isDocument = state.type == MemoryType.DOCUMENT &&
                            state.ingestedChunkCount != null,
                        chunkCount = state.ingestedChunkCount,
                        onContinue = viewModel::onCreatedAcknowledged,
                        onAddAnother = viewModel::onAddAnother,
                    )
                } else {
                    ComposerCard(
                        state = state,
                        viewModel = viewModel,
                        onPickDocument = {
                            documentLauncher.launch(viewModel.supportedDocumentMimeTypes)
                        },
                    )
                }
            }
            is LoadableState.Failed -> EdgeErrorState(dataState.message, onRetry = { /* no-op */ })
            is LoadableState.Loading -> EdgeLoadingState("Preparing…") // should not occur
        }
    }
}

@Composable
private fun ComposerCard(
    state: CreateRecordUiState,
    viewModel: CreateRecordViewModel,
    onPickDocument: () -> Unit,
) {
    EdgeCard(modifier = Modifier.fillMaxWidth().testTag("edge-create-record-composer")) {
        TechLabel(text = "CREATE RECORD")
        EdgeSpacer(EdgeDimens.spacingS)

        if (state.subjectLocked) {
            // Machine capture: the subject is fixed to this machine's namespace,
            // so every initial record stays isolated — show it read-only.
            Text("FOR MACHINE", style = EdgeType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            EdgeSpacer(EdgeDimens.spacingXs)
            Row(
                modifier = Modifier.fillMaxWidth().testTag("edge-create-subject-locked"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
            ) {
                StatusChip(
                    text = state.machineName ?: state.subject,
                    color = MaterialTheme.colorScheme.onPrimary,
                    containerColor = MaterialTheme.colorScheme.primary,
                    showDot = true,
                )
                Text(
                    text = "#${state.subject}",
                    style = EdgeType.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LabeledField(
                label = "ASSET / SUBJECT",
                value = state.subject,
                placeholder = "e.g. P-101, LINE-A, PUMP-3",
                tag = "edge-create-subject",
                onValueChange = viewModel::onSubjectChange,
            )
        }
        EdgeSpacer(EdgeDimens.spacingXs)

        LabeledField(
            label = "TITLE",
            value = state.title,
            placeholder = "Short title (required if no content)",
            tag = "edge-create-title",
            onValueChange = viewModel::onTitleChange,
        )
        EdgeSpacer(EdgeDimens.spacingXs)

        LabeledField(
            label = "DETAIL",
            value = state.content,
            placeholder = "Observation, repair steps, procedure, etc.",
            tag = "edge-create-content",
            singleLine = false,
            onValueChange = viewModel::onContentChange,
        )
        EdgeSpacer(EdgeDimens.spacingXs)

        LabeledField(
            label = "TAGS (optional, comma-separated)",
            value = state.tags,
            placeholder = "seal, vibration, bearing",
            tag = "edge-create-tags",
            onValueChange = viewModel::onTagsChange,
        )
        EdgeSpacer(EdgeDimens.spacingXs)

        Text("TYPE", style = EdgeType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
        ) {
            MemoryType.entries.forEach { type ->
                TypeChip(
                    type = type,
                    selected = state.type == type,
                    onClick = { viewModel.onTypeChange(type) },
                )
            }
        }
        EdgeSpacer(EdgeDimens.spacingXs)

        // DOCUMENT type: real system file picker (SAF) for the supported
        // document types (PDF / TXT / MD) the ingestion pipeline can extract.
        if (state.type == MemoryType.DOCUMENT) {
            Text("DOCUMENT", style = EdgeType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            EdgeSpacer(EdgeDimens.spacingXs)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
            ) {
                PillButton(
                    text = "Select document",
                    onClick = onPickDocument,
                    enabled = !state.isBusy,
                    modifier = Modifier.testTag("edge-create-select-document"),
                )
                TonalPill(
                    text = if (state.documentName != null) "Change" else "Cancel",
                    onClick = if (state.documentName != null) onPickDocument else viewModel::onDocumentClear,
                )
            }
            EdgeSpacer(EdgeDimens.spacingXs)
            // Honest status of the picked/ingesting document.
            val label = when {
                state.documentName != null -> state.documentName!!
                state.documentUri != null -> "Selected document"
                else -> "No document selected"
            }
            Text(
                text = label,
                style = EdgeType.metadata,
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
                if (stage != com.example.EdgeMemo.domain.document.IngestionStage.IDLE &&
                    stage != com.example.EdgeMemo.domain.document.IngestionStage.COMPLETED
                ) {
                    EdgeSpacer(EdgeDimens.spacingXs)
                    Text(
                        text = "Ingesting \u00B7 ${stage.name.lowercase()}",
                        style = EdgeType.metadata,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("edge-create-ingestion-stage"),
                    )
                }
            }
            EdgeSpacer(EdgeDimens.spacingXs)
        }

        Text("SYNC", style = EdgeType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
        ) {
            SyncChip("Auto (policy)", state.syncChoice == null, { viewModel.onSyncChoiceChange(null) })
            SyncChip("Sync", state.syncChoice == SyncDecision.SYNC, { viewModel.onSyncChoiceChange(SyncDecision.SYNC) })
            SyncChip("Local only", state.syncChoice == SyncDecision.LOCAL_ONLY, { viewModel.onSyncChoiceChange(SyncDecision.LOCAL_ONLY) })
        }
        EdgeSpacer(EdgeDimens.spacingS)

        state.error?.let {
            Text(text = it, style = EdgeType.metadata, color = MaterialTheme.colorScheme.error)
            EdgeSpacer(EdgeDimens.spacingXs)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
        ) {
            PillButton(
                text = if (state.isBusy) "Saving…" else "Save to memory",
                onClick = viewModel::submit,
                enabled = !state.isBusy && state.isFormValid,
                modifier = Modifier.testTag("edge-create-submit"),
            )
            TonalPill(text = "Cancel", onClick = viewModel::cancel)
        }

        Text(
            text = "Saved through CreateMemoryUseCase: policy evaluation, local Qdrant persistence " +
                "and change detection all run before the real sync state is shown.",
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CreatedConfirmationCard(
    memory: Memory,
    isDocument: Boolean,
    chunkCount: Int?,
    onContinue: () -> Unit,
    onAddAnother: () -> Unit,
) {
    EdgeCard(modifier = Modifier.fillMaxWidth().testTag("edge-create-record-created")) {
        TechLabel(text = "RECORD CREATED", color = MaterialTheme.colorScheme.primary)
        EdgeSpacer(EdgeDimens.spacingXs)

        Text(
            text = memory.title.ifBlank { memory.memoryId.take(8) },
            style = EdgeType.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        EdgeSpacer(EdgeDimens.spacingXs)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
        ) {
            StatusChip(
                text = memory.type.name,
                color = MaterialTheme.colorScheme.primary,
                showDot = true,
            )
            StatusChip(
                text = memory.subjectKey ?: "—",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                showDot = false,
            )
        }
        EdgeSpacer(EdgeDimens.spacingXs)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
        ) {
            StatusChip(
                text = when (memory.syncState) {
                    MemorySyncState.PENDING -> "pending sync"
                    MemorySyncState.FAILED -> "sync failed"
                    MemorySyncState.SYNCED -> "synced"
                    MemorySyncState.LOCAL -> "local only"
                },
                color = when (memory.syncState) {
                    MemorySyncState.PENDING -> MaterialTheme.colorScheme.tertiary
                    MemorySyncState.FAILED -> MaterialTheme.colorScheme.error
                    MemorySyncState.SYNCED -> MaterialTheme.colorScheme.primary
                    MemorySyncState.LOCAL -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                showDot = true,
            )
            if (memory.syncDecision != SyncDecision.LOCAL_ONLY) {
                StatusChip(
                    text = memory.syncDecision.name.lowercase().replace('_', ' '),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    showDot = false,
                )
            }
        }
        EdgeSpacer(EdgeDimens.spacingXs)

        if (isDocument && chunkCount != null) {
            Text(
                text = "Ingested $chunkCount searchable ${if (chunkCount == 1) "chunk" else "chunks"} " +
                    "through the local document pipeline.",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("edge-create-document-summary"),
            )
        } else {
            Text(
                text = "The asset \"${CreateRecordModel.namespaceOf(memory.subjectKey ?: "")}\" is now available in Machines.",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        EdgeSpacer(EdgeDimens.spacingS)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
        ) {
            PillButton(text = "Open asset", onClick = onContinue, modifier = Modifier.testTag("edge-create-open-asset"))
            TonalPill(
                text = "Add another record",
                onClick = onAddAnother,
                modifier = Modifier.testTag("edge-create-add-another"),
            )
        }
    }
}

@Composable
private fun TypeChip(
    type: MemoryType,
    selected: Boolean,
    onClick: () -> Unit,
) {
    StatusChip(
        text = type.name,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        showDot = true,
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Record type ${type.name}" },
    )
}

@Composable
private fun SyncChip(label: String, selected: Boolean, onClick: () -> Unit) {
    StatusChip(
        text = label,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        showDot = false,
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Sync choice $label" },
    )
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    placeholder: String,
    tag: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = EdgeType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (singleLine) 40.dp else 64.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                )
                .padding(EdgeLayout.cardGap),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 6,
                textStyle = EdgeType.body.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    imeAction = if (singleLine) ImeAction.Next else ImeAction.Default
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(tag)
                    .semantics { contentDescription = label.lowercase() },
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = EdgeType.metadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    inner()
                },
            )
        }
    }
}