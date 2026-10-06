package com.example.EdgeMemo.presentation.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.BuildConfig
import com.example.EdgeMemo.presentation.components.EdgeDimens
import com.example.EdgeMemo.presentation.components.EdgeListGroup
import com.example.EdgeMemo.presentation.components.SectionHeader
import com.example.EdgeMemo.presentation.components.TonalPill
import com.example.EdgeMemo.presentation.memory.cloudPullSummary
import com.example.EdgeMemo.presentation.memory.MemoryViewModel
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus
import com.example.EdgeMemo.ui.theme.statusStyleFor

/**
 * Settings only exposes real, existing functionality: theme (persisted),
 * the local profile name, real record/outbox counts, cloud pull, the fixed
 * answer contract and build identity. The shell draws the title; [onBack] is
 * kept for callers but no second back control is drawn here.
 */
@Composable
fun SettingsScreen(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    profileName: String,
    onProfileNameChange: (String) -> Unit,
    memoryViewModel: MemoryViewModel,
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
) {
    val state by memoryViewModel.uiState.collectAsState()

    // The MemoryViewModel is Activity-scoped and only loads on creation or its
    // own actions; records created elsewhere (seeding, Machines, the record
    // composer) leave it stale. Reload real counts every time Settings shows.
    LaunchedEffect(Unit) { memoryViewModel.refresh() }

    val summary = state.syncSummary
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = EdgeLayout.screenPadding,
            end = EdgeLayout.screenPadding,
            top = EdgeDimens.spacingS,
            bottom = EdgeDimens.spacing2xl,
        ),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.sectionGap),
    ) {
        item {
            Section("Appearance") {
                EdgeListGroup(listOf(Unit)) {
                    SettingRow("Theme") {
                        ThemeSegments(darkTheme = darkTheme, onToggleTheme = onToggleTheme)
                    }
                }
            }
        }
        item {
            Section("Profile") {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = onProfileNameChange,
                    label = { Text("Your name") },
                    supportingText = { Text("Used in the greeting. Stays on this device.") },
                    singleLine = true,
                    shape = RoundedCornerShape(EdgeDimens.inputRadius),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Section("Records") {
                EdgeListGroup(
                    listOf(
                        "Records on this device" to state.memoryCount.toString(),
                        "Search" to "Works offline",
                    ),
                ) { (label, value) -> ValueRow(label, value) }
            }
        }
        item {
            Section("Sync") {
                EdgeListGroup(
                    listOf(
                        Triple("Queued", summary.pending, EdgeStatus.WARNING),
                        Triple("Syncing", summary.syncing, EdgeStatus.SYNCING),
                        Triple("Synced", summary.synced, EdgeStatus.SYNCED),
                        Triple("Failed", summary.failed, EdgeStatus.CRITICAL),
                        Triple("Only on this device", summary.localOnly, EdgeStatus.WARNING),
                    ),
                ) { (label, count, status) -> CountRow(label, count, status) }
            }
        }
        item {
            Section("Cloud") {
                val cloudRows = buildList<@Composable () -> Unit> {
                    add {
                        ValueRow(
                            "Cloud server",
                            if (BuildConfig.CLOUD_BACKEND_URL.isBlank()) "Not set up" else "Set up",
                        )
                    }
                    add {
                        SettingRow("Shared knowledge", supporting = cloudPullSummary(state.pullStatus)) {
                            TonalPill(
                                text = "Pull now",
                                onClick = memoryViewModel::pullCloud,
                                enabled = !state.isBusy,
                            )
                        }
                    }
                    if (state.unresolvedConflictCount > 0) {
                        add {
                            CountRow("Conflicts to review", state.unresolvedConflictCount, EdgeStatus.WARNING)
                        }
                    }
                }
                EdgeListGroup(cloudRows) { it() }
            }
        }
        item {
            Section("Answers") {
                EdgeListGroup(
                    listOf(
                        "Answers come from" to "Records on this device",
                        "If records don't cover it" to "Says so",
                        "Cloud help" to "Only when online",
                        "Cloud answers" to "Saved only if you save them",
                    ),
                ) { (label, value) -> ValueRow(label, value) }
            }
        }
        item {
            Section("About") {
                EdgeListGroup(listOf(Unit)) {
                    ValueRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        SectionHeader(title = title)
        content()
    }
}

/** Label left, control or value right; 56dp tall so it reads as a list row. */
@Composable
private fun SettingRow(
    label: String,
    supporting: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = EdgeDimens.spacingL, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.padding(start = EdgeDimens.spacingM)) { trailing() }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    SettingRow(label) {
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

/** Count row: the number turns to its status color only when it is non-zero. */
@Composable
private fun CountRow(label: String, count: Long, status: EdgeStatus) {
    SettingRow(label) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = if (count > 0) statusStyleFor(status).color else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Two-option segmented control. Selected = raised fill; iris marks the tap target. */
@Composable
private fun ThemeSegments(darkTheme: Boolean, onToggleTheme: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(3.dp).selectableGroup()) {
            listOf("Dark" to true, "Light" to false).forEach { (label, isDark) ->
                val selected = darkTheme == isDark
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier
                        .width(72.dp)
                        .heightIn(min = 42.dp)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { if (!selected) onToggleTheme() },
                        ),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 11.dp),
                    )
                }
            }
        }
    }
}
