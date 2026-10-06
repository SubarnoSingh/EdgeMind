package com.example.EdgeMemo.presentation.machines

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import com.example.EdgeMemo.core.sync.SyncSummary
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeCardSecondary
import com.example.EdgeMemo.presentation.components.EdgeEmptyState
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.EdgeLoadingState
import com.example.EdgeMemo.presentation.components.EdgeUiTags
import com.example.EdgeMemo.presentation.components.PillButton
import com.example.EdgeMemo.presentation.components.StatusDot
import com.example.EdgeMemo.presentation.components.TechLabel
import com.example.EdgeMemo.presentation.components.TonalPill
import com.example.EdgeMemo.presentation.components.relativeTimeLabel
import com.example.EdgeMemo.presentation.machines.AssetModel.Asset
import com.example.EdgeMemo.presentation.shell.LoadableState
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeType

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
            .padding(horizontal = EdgeLayout.screenPadding, vertical = EdgeLayout.cardGap),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
    ) {
        TechLabel(text = "Machines & assets")
        AddMachineSection(viewModel, creation)
        when (val s = state) {
            LoadableState.Loading -> EdgeLoadingState("Reading asset knowledge…")
            is LoadableState.Failed -> EdgeErrorState(s.message, onRetry = viewModel::refresh)
            is LoadableState.Ready -> {
                if (s.value.isEmpty()) {
            @Composable
            fun AddRecordAction() {
                PillButton(
                    text = "+ Add Record",
                    onClick = viewModel.navigator::openCreateRecord,
                    modifier = Modifier.testTag("edge-machines-add-record"),
                )
            }
            EdgeEmptyState(
                title = "No machine or asset references yet",
                message = "Assets appear here when local records reference an " +
                    "equipment subject (for example a pump or line tag). " +
                    "Capture notes, ingest procedures, or sync cloud knowledge " +
                    "to populate this view — nothing is faked.",
                action = ::AddRecordAction,
            )} else {
                    Text(
                        text = "Derived from ${s.value.sumOf { it.recordCount }} local records " +
                            "across ${s.value.size} asset namespaces.",
                        style = EdgeType.metadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    s.value.forEach { asset ->
                        AssetCard(
                            asset = asset,
                            nowMillis = nowMillis(),
                            sync = sync,
                            isOnline = isOnline,
                            onClick = { viewModel.openAsset(asset.namespace) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssetCard(
    asset: Asset,
    nowMillis: Long,
    sync: SyncSummary,
    isOnline: Boolean,
    onClick: () -> Unit,
) {
    val cardStatus = MachinesAssetStatus.of(asset, sync, isOnline)
    val status = cardStatus.status
    EdgeCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${EdgeUiTags.MACHINE_CARD_PREFIX}${asset.namespace}")
            .semantics {
                contentDescription =
                    "Asset ${asset.namespace}: ${asset.recordCount} records, " +
                        "${asset.maintenanceCount} maintenance entries, " +
                        "sync ${cardStatus.label}, " +
                        "last activity ${relativeTimeLabel(asset.lastActivityAt, nowMillis)}"
            }
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = asset.namespace.uppercase(),
                style = EdgeType.numeric,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatusDot(
                status = status,
                label = cardStatus.label,
                modifier = Modifier.testTag(
                    "${EdgeUiTags.MACHINE_CARD_STATUS_PREFIX}${asset.namespace}",
                ),
            )
        }
        Text(
            text = asset.representativeTitle,
            style = EdgeType.bodyEmphasis,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.padding(vertical = 2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
        ) {
            Text(
                text = "${asset.recordCount} records · ${asset.maintenanceCount} maintenance",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = relativeTimeLabel(asset.lastActivityAt, nowMillis),
                style = EdgeType.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (asset.types.isNotEmpty()) {
            Spacer(Modifier.padding(vertical = 2.dp))
            Text(
                text = asset.types.joinToString(" · ") { it.name.lowercase() },
                style = EdgeType.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Add-machine affordance. Collapsed: a single pill (always visible above the
 * list) plus a transient "created" confirmation. Expanded: a compact inline
 * form that persists a REAL machine-definition record through the production
 * create path — no fake list entry, no separate store.
 */
@Composable
private fun AddMachineSection(viewModel: MachinesViewModel, creation: AddMachineState) {
    if (creation.open) {
        EdgeCardSecondary(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("edge-machines-add-machine-form"),
        ) {
            TechLabel(text = "New machine / asset")
            Spacer(Modifier.padding(vertical = 2.dp))
            MachineField(
                label = "Machine ID",
                value = creation.id,
                placeholder = "e.g. P-102",
                tag = "edge-machines-machine-id",
                onValueChange = viewModel::onMachineIdChange,
            )
            Spacer(Modifier.padding(vertical = 4.dp))
            MachineField(
                label = "Name / Description",
                value = creation.name,
                placeholder = "optional — coolant pump",
                tag = "edge-machines-machine-name",
                onValueChange = viewModel::onMachineNameChange,
            )
            creation.error?.let { message ->
                Spacer(Modifier.padding(vertical = 4.dp))
                Text(
                    text = message,
                    style = EdgeType.metadata,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("edge-machines-add-machine-error"),
                )
            }
            Spacer(Modifier.padding(vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap)) {
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
        return
    }

    // Created: offer the initial-record workflow instead of dumping the user
    // back on the bare list, so a machine can be populated in one pass.
    creation.createdId?.let { id ->
        EdgeCardSecondary(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("edge-machines-created"),
        ) {
            TechLabel(text = "Machine created")
            Spacer(Modifier.padding(vertical = 2.dp))
            Text(
                text = "${creation.createdName ?: id.uppercase()} ($id) is ready.",
                style = EdgeType.bodyEmphasis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.padding(vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap)) {
                PillButton(
                    text = "Add initial record",
                    onClick = viewModel::addInitialRecord,
                    modifier = Modifier.testTag("edge-machines-add-initial-record"),
                )
                TonalPill(
                    text = "Skip for now",
                    onClick = viewModel::skipInitialRecords,
                    modifier = Modifier.testTag("edge-machines-skip"),
                )
            }
        }
        return
    }

    PillButton(
        text = "+ Add Machine",
        onClick = viewModel::openAddMachine,
        modifier = Modifier.testTag("edge-machines-add-machine"),
    )
}

@Composable
private fun MachineField(
    label: String,
    value: String,
    placeholder: String,
    tag: String,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = EdgeType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    RoundedCornerShape(10.dp),
                )
                .padding(EdgeLayout.cardGap),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = EdgeType.body.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
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
