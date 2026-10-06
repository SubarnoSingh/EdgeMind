package com.example.EdgeMemo.presentation.machines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.core.sync.SyncSummary
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeCardSecondary
import com.example.EdgeMemo.presentation.components.EdgeDimens
import com.example.EdgeMemo.presentation.components.EdgeEmptyState
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.EdgeListGroup
import com.example.EdgeMemo.presentation.components.EdgeLoadingState
import com.example.EdgeMemo.presentation.components.EdgeUiTags
import com.example.EdgeMemo.presentation.components.PillButton
import com.example.EdgeMemo.presentation.components.StatusDot
import com.example.EdgeMemo.presentation.components.TonalPill
import com.example.EdgeMemo.presentation.components.relativeTimeLabel
import com.example.EdgeMemo.presentation.machines.AssetModel.Asset
import com.example.EdgeMemo.presentation.shell.LoadableState
import com.example.EdgeMemo.ui.theme.EdgeLayout

@Composable
fun MachinesScreen(
    viewModel: MachinesViewModel,
    modifier: Modifier = Modifier,
    nowMillis: () -> Long = { System.currentTimeMillis() },
    sync: SyncSummary = SyncSummary(),
    isOnline: Boolean = true,
) {
    val state by viewModel.uiState.collectAsState()
    val creation by viewModel.creation.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(EdgeUiTags.MACHINES)
            .verticalScroll(rememberScrollState())
            .padding(
                start = EdgeLayout.screenPadding,
                end = EdgeLayout.screenPadding,
                top = EdgeLayout.compactGap,
                bottom = EdgeLayout.sectionGap,
            ),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
    ) {
        val assets = (state as? LoadableState.Ready)?.value
        if (creation.open) {
            AddMachineForm(viewModel, creation)
        } else if (creation.createdId != null) {
            MachineCreated(viewModel, creation)
        }
        when (val s = state) {
            LoadableState.Loading -> EdgeLoadingState("Loading machines…")
            is LoadableState.Failed -> EdgeErrorState(s.message, onRetry = viewModel::refresh)
            is LoadableState.Ready -> if (assets.isNullOrEmpty()) {
                EdgeEmptyState(
                    title = "No machines yet",
                    message = "Add a machine, or log a record that names one (like P-101) " +
                        "and it shows up here.",
                    action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
                            if (!creation.open) {
                                PillButton(
                                    text = "Add machine",
                                    onClick = viewModel::openAddMachine,
                                    modifier = Modifier.testTag("edge-machines-add-machine"),
                                )
                            }
                            TonalPill(
                                text = "Add record",
                                onClick = viewModel.navigator::openCreateRecord,
                                modifier = Modifier.testTag("edge-machines-add-record"),
                            )
                        }
                    },
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (assets.size == 1) "1 machine" else "${assets.size} machines",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (!creation.open) {
                        TextButton(
                            onClick = viewModel::openAddMachine,
                            modifier = Modifier.testTag("edge-machines-add-machine"),
                        ) {
                            Text("Add machine", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                val now = nowMillis()
                EdgeListGroup(assets) { asset ->
                    MachineRow(
                        asset = asset,
                        nowMillis = now,
                        sync = sync,
                        isOnline = isOnline,
                        onClick = { viewModel.openAsset(asset.namespace) },
                    )
                }
            }
        }
    }
}

/** One machine, read like its nameplate: tag, what it is, one line of real facts, real status. */
@Composable
private fun MachineRow(
    asset: Asset,
    nowMillis: Long,
    sync: SyncSummary,
    isOnline: Boolean,
    onClick: () -> Unit,
) {
    val cardStatus = MachinesAssetStatus.of(asset, sync, isOnline)
    val tag = asset.namespace.uppercase()
    val lastActivity = recordTimeLabel(asset.lastActivityAt, nowMillis)
    val facts = when (asset.recordCount) {
        0 -> "Added $lastActivity"
        1 -> "1 record, latest $lastActivity"
        else -> "${asset.recordCount} records, latest $lastActivity"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${EdgeUiTags.MACHINE_CARD_PREFIX}${asset.namespace}")
            .semantics {
                contentDescription =
                    "Machine $tag: ${asset.recordCount} records, " +
                        "${asset.maintenanceCount} maintenance entries, " +
                        "${cardStatus.label}, " +
                        "last activity ${relativeTimeLabel(asset.lastActivityAt, nowMillis)}"
            }
            .clickable(onClick = onClick)
            .padding(horizontal = EdgeDimens.spacingL, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tag,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatusDot(
                status = cardStatus.status,
                label = cardStatus.label,
                modifier = Modifier
                    .padding(start = EdgeDimens.spacingM)
                    .testTag("${EdgeUiTags.MACHINE_CARD_STATUS_PREFIX}${asset.namespace}"),
            )
        }
        val description = describe(asset.representativeTitle, asset.namespace)
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = facts,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Inline add-machine form. Persists a REAL machine-definition record through
 * the production create path (see [MachinesViewModel.addMachine]).
 */
@Composable
private fun AddMachineForm(viewModel: MachinesViewModel, creation: AddMachineState) {
    EdgeCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("edge-machines-add-machine-form"),
    ) {
        Text(
            "New machine",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(EdgeDimens.spacingM))
        MachineField(
            label = "Machine ID",
            value = creation.id,
            placeholder = "P-102",
            tag = "edge-machines-machine-id",
            onValueChange = viewModel::onMachineIdChange,
        )
        Spacer(Modifier.height(EdgeLayout.cardGap))
        MachineField(
            label = "Name (optional)",
            value = creation.name,
            placeholder = "Coolant pump",
            tag = "edge-machines-machine-name",
            onValueChange = viewModel::onMachineNameChange,
        )
        creation.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = EdgeLayout.cardGap)
                    .testTag("edge-machines-add-machine-error"),
            )
        }
        Spacer(Modifier.height(EdgeDimens.spacingL))
        Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
            PillButton(
                text = if (creation.submitting) "Creating…" else "Create machine",
                onClick = viewModel::addMachine,
                enabled = !creation.submitting,
                modifier = Modifier.testTag("edge-machines-create"),
            )
            TonalPill(
                text = "Cancel",
                onClick = viewModel::closeAddMachine,
                modifier = Modifier.testTag("edge-machines-add-machine-cancel"),
            )
        }
    }
}

/** After creating: offer the first record right away, or open the machine. */
@Composable
private fun MachineCreated(viewModel: MachinesViewModel, creation: AddMachineState) {
    val id = creation.createdId ?: return
    val tag = id.uppercase()
    EdgeCardSecondary(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("edge-machines-created"),
    ) {
        Text(
            text = creation.createdName?.let { "$it ($tag) added" } ?: "$tag added",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Log a first record so there's something to look back on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(EdgeDimens.spacingM))
        Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
            PillButton(
                text = "Add first record",
                onClick = viewModel::addInitialRecord,
                modifier = Modifier.testTag("edge-machines-add-initial-record"),
            )
            TonalPill(
                text = "Open $tag",
                onClick = viewModel::skipInitialRecords,
                modifier = Modifier.testTag("edge-machines-skip"),
            )
        }
    }
}

@Composable
private fun MachineField(
    label: String,
    value: String,
    placeholder: String,
    tag: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = RoundedCornerShape(EdgeDimens.pillRadius),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    )
}

/**
 * The record title that describes a machine, minus a leading repeat of its
 * own tag ("P-101 Cavitation check" under P-101 reads "Cavitation check").
 * Null when nothing is left to say.
 */
internal fun describe(title: String, namespace: String): String? {
    val tag = namespace.uppercase()
    val rest = if (title.startsWith(tag, ignoreCase = true)) {
        title.drop(tag.length).trimStart(' ', '-', ':', '–', '—', ',')
    } else {
        title.trim()
    }
    return rest.takeIf { it.isNotBlank() && !it.equals(namespace, ignoreCase = true) }
}
