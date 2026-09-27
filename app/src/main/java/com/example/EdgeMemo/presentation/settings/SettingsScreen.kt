package com.example.EdgeMemo.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.BuildConfig
import com.example.EdgeMemo.presentation.components.EdgeDimens
import com.example.EdgeMemo.presentation.components.MoonIcon
import com.example.EdgeMemo.presentation.components.SectionHeader
import com.example.EdgeMemo.presentation.components.StatusChip
import com.example.EdgeMemo.presentation.components.SunIcon
import com.example.EdgeMemo.presentation.components.TonalPill
import com.example.EdgeMemo.presentation.memory.CloudPullStatus
import com.example.EdgeMemo.presentation.memory.MemoryViewModel
import com.example.EdgeMemo.ui.theme.LocalEdgeColors

/**
 * Settings only exposes real, existing functionality:
 *  - APPEARANCE: theme toggle (real, persisted)
 *  - PERSONALIZATION: local user name (offline, personalized greetings only)
 *  - MEMORY: counts (real Room/Qdrant state)
 *  - SYNCHRONIZATION: real outbox summary + cloud pull
 *  - ASK: informational (the real local-first + escalation contract)
 *  - ABOUT: build identity
 */
@Composable
fun SettingsScreen(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    profileName: String,
    onProfileNameChange: (String) -> Unit,
    memoryViewModel: MemoryViewModel,
    onBack: () -> Unit,
) {
    val memoryState by memoryViewModel.uiState.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EdgeDimens.spacingS, vertical = EdgeDimens.spacingS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = EdgeDimens.spacingL,
                end = EdgeDimens.spacingL,
                bottom = EdgeDimens.spacing2xl,
            ),
            verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingM),
        ) {
            item { AppearanceSection(darkTheme, onToggleTheme) }
            item { PersonalizationSection(profileName, onProfileNameChange) }
            item { MemorySection(memoryState.memoryCount) }
            item { SyncSection(memoryViewModel) }
            item { AskBehaviorSection() }
            item { AboutSection() }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    // Use a more distinct surface color for better contrast in dark mode
    val containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    
    Surface(
        shape = RoundedCornerShape(EdgeDimens.cardRadius),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, borderColor),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(EdgeDimens.spacingL),
            verticalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
        ) {
            SectionHeader(title = title, subtitle = subtitle)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            content()
        }
    }
}

@Composable
private fun PersonalizationSection(name: String, onNameChange: (String) -> Unit) {
    SettingsCard(
        title = "Personalization",
        subtitle = "Your display name is used for the greeting. Local only — it never leaves this device.",
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Name") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AppearanceSection(darkTheme: Boolean, onToggleTheme: () -> Unit) {
    SettingsCard(title = "Appearance", subtitle = "Choose how EdgeMind looks on this device.") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingM)) {
                Icon(
                    imageVector = if (darkTheme) MoonIcon else SunIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                    Text(
                        text = if (darkTheme) "Dark appearance" else "Light appearance",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "tap to switch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TonalPill(
                text = if (darkTheme) "Use light" else "Use dark",
                onClick = onToggleTheme,
            )
        }
    }
}

@Composable
private fun MemorySection(memoryCount: Long) {
    SettingsCard(title = "Memory", subtitle = "Your knowledge stays searchable offline.") {
        SettingRow(
            label = "Memories on device",
            value = memoryCount.toString(),
        )
        SettingRow(
            label = "Vector storage",
            value = "Qdrant Edge (local)",
        )
        SettingRow(
            label = "Embeddings",
            value = "on-device",
        )
    }
}

@Composable
private fun SyncSection(viewModel: MemoryViewModel) {
    val state by viewModel.uiState.collectAsState()
    val edgeColors = LocalEdgeColors.current
    val summary = state.syncSummary
    val total = summary.total
    SettingsCard(
        title = "Synchronization",
        subtitle = "Durable outbox. Nothing leaves the device without policy approval.",
    ) {
        SettingRow(label = "Local only", value = summary.localOnly.toString())
        SettingRow(label = "Pending", value = summary.pending.toString())
        SettingRow(label = "Syncing", value = summary.syncing.toString())
        SettingRow(label = "Acknowledged", value = summary.synced.toString())
        SettingRow(label = "Failed (retrying)", value = summary.failed.toString())
        SettingRow(
            label = "Cloud backend",
            value = if (BuildConfig.CLOUD_BACKEND_URL.isBlank()) "not configured" else "configured",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "Cloud knowledge",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = pullStatusText(state.pullStatus),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TonalPill(
                text = "Pull cloud",
                onClick = viewModel::pullCloud,
                icon = Icons.Filled.Refresh,
                enabled = !state.isBusy,
            )
        }
        if (state.unresolvedConflictCount > 0) {
            StatusChip(
                text = "${state.unresolvedConflictCount} unresolved ${if (state.unresolvedConflictCount == 1L) "conflict" else "conflicts"}",
                color = edgeColors.accentAmber,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            )
        }
    }
}

private fun pullStatusText(status: CloudPullStatus): String = when (status) {
    CloudPullStatus.Idle -> "shared knowledge arrives here when connectivity returns."
    CloudPullStatus.Unavailable -> "no cloud backend configured \u2014 nothing fabricated."
    is CloudPullStatus.Success -> "received ${status.result.applied} new/updated \u00b7 ${status.result.conflicts} conflicts."
    is CloudPullStatus.Failed -> status.message
}

@Composable
private fun AskBehaviorSection() {
    SettingsCard(
        title = "Ask behavior",
        subtitle = "How answers are produced. This contract is fixed by design.",
    ) {
        SettingRow(label = "First", value = "local memory (offline)")
        SettingRow(label = "If evidence is insufficient", value = "honest limitation")
        SettingRow(label = "Cloud escalation", value = "only when online")
        SettingRow(label = "Cloud answers", value = "never saved automatically")
    }
}

@Composable
private fun AboutSection() {
    SettingsCard(title = "About", subtitle = "EdgeMind \u2014 AI work memory for the edge.") {
        SettingRow(label = "Version", value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        SettingRow(label = "Mode", value = "offline-first")
        SettingRow(
            label = "Core",
            value = "local RAG \u00b7 policy \u00b7 durable sync \u00b7 conflicts",
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(start = EdgeDimens.spacingL),
        )
    }
}
