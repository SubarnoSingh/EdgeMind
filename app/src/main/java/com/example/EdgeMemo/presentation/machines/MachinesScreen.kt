package com.example.EdgeMemo.presentation.machines

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
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeEmptyState
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.EdgeLoadingState
import com.example.EdgeMemo.presentation.components.EdgeUiTags
import com.example.EdgeMemo.presentation.components.PillButton
import com.example.EdgeMemo.presentation.components.StatusDot
import com.example.EdgeMemo.presentation.components.TechLabel
import com.example.EdgeMemo.presentation.components.relativeTimeLabel
import com.example.EdgeMemo.presentation.machines.AssetModel.Asset
import com.example.EdgeMemo.presentation.shell.LoadableState
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus
import com.example.EdgeMemo.ui.theme.EdgeType

@Composable
fun MachinesScreen(
    viewModel: MachinesViewModel,
    modifier: Modifier = Modifier,
    nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    val state by viewModel.uiState.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(EdgeUiTags.MACHINES)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = EdgeLayout.screenPadding, vertical = EdgeLayout.cardGap),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
    ) {
        TechLabel(text = "Machines & assets")
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
                        AssetCard(asset = asset, nowMillis = nowMillis(), onClick = {
                            viewModel.openAsset(asset.namespace)
                        })
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
    onClick: () -> Unit,
) {
    val status = when {
        asset.unresolvedConflictCount > 0L -> EdgeStatus.WARNING
        asset.pendingSyncCount > 0 -> EdgeStatus.SYNCING
        asset.recordCount > 0 -> EdgeStatus.HEALTHY
        else -> EdgeStatus.NEUTRAL
    }
    EdgeCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${EdgeUiTags.MACHINE_CARD_PREFIX}${asset.namespace}")
            .semantics {
                contentDescription =
                    "Asset ${asset.namespace}: ${asset.recordCount} records, " +
                        "${asset.maintenanceCount} maintenance entries, " +
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
            StatusDot(status = status, label = status.name.lowercase())
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
