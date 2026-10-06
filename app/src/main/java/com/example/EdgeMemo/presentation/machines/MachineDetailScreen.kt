package com.example.EdgeMemo.presentation.machines

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.sync.SyncSummary
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeCardSecondary
import com.example.EdgeMemo.presentation.components.EdgeDimens
import com.example.EdgeMemo.presentation.components.EdgeEmptyState
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.EdgeListGroup
import com.example.EdgeMemo.presentation.components.EdgeLoadingState
import com.example.EdgeMemo.presentation.components.EdgeUiTags
import com.example.EdgeMemo.presentation.components.PillButton
import com.example.EdgeMemo.presentation.components.SectionHeader
import com.example.EdgeMemo.presentation.components.StatusDot
import com.example.EdgeMemo.presentation.components.TonalPill
import com.example.EdgeMemo.presentation.machines.AssetModel.AssetRecordCategory
import com.example.EdgeMemo.presentation.shell.LoadableState
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus
import com.example.EdgeMemo.ui.theme.EdgeType

/** UI Phase 3 test tags. */
object AssetUiTags {
    const val SCREEN = "edge-asset-workspace"
    const val OVERVIEW = "edge-asset-overview"
    const val TIMELINE = "edge-asset-timeline"
    const val TIMELINE_ITEM_PREFIX = "edge-asset-record-"
    const val MAINTENANCE = "edge-asset-maintenance"
    const val OBSERVATIONS = "edge-asset-observations"
    const val INCIDENTS = "edge-asset-incidents"
    const val PROCEDURES = "edge-asset-procedures"
    const val DOCUMENTS = "edge-asset-documents"
    const val SECTION_ITEM_PREFIX = "edge-asset-section-record-"
    const val CONFLICTS = "edge-asset-conflicts"
    const val CONFLICT_PREFIX = "edge-asset-conflict-"
    const val RECORD_CONFLICT_PREFIX = "edge-asset-record-conflict-"
    const val REFRESH = "edge-asset-refresh"
    const val FILTER_PREFIX = "edge-asset-filter-"
    const val ADD_OBSERVATION = "edge-asset-add-observation"
    const val ADD_EVENT = "edge-asset-add-event"
    const val ADD_PROCEDURE = "edge-asset-add-procedure"
    const val LOG_MAINTENANCE = "edge-asset-log-maintenance"
    const val COMPOSER = "edge-asset-composer"
    const val COMPOSER_TITLE = "edge-asset-composer-title"
    const val COMPOSER_CONTENT = "edge-asset-composer-content"
    const val COMPOSER_SUBMIT = "edge-asset-composer-submit"
    const val CREATED_MESSAGE = "edge-asset-created"
    const val CREATED_SYNC = "edge-asset-created-sync"
    const val TYPE_PREFIX = "edge-asset-type-"
    const val EMPTY = "edge-asset-empty"
}

/**
 * One machine: its nameplate, what needs attention, and every record stored
 * against it. Everything is derived from REAL stored records for the subject
 * namespace; no machine attributes (health, criticality, telemetry) are
 * invented. Back is drawn by the shell header; [onBack] stays for callers.
 */
@Composable
fun MachineDetailScreen(
    viewModel: MachineDetailViewModel,
    namespace: String,
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    val state by viewModel.uiState.collectAsState()
    // Re-read the durable store whenever this screen (re)enters composition —
    // e.g. returning after resolving a conflict, so resolved rows disappear.
    LaunchedEffect(Unit) { viewModel.refresh() }
    val data = state.data
    val ready = (data as? LoadableState.Ready)?.value
    val now = nowMillis()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(AssetUiTags.SCREEN),
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .testTag(EdgeUiTags.MACHINE_DETAIL)
                .padding(
                    start = EdgeLayout.screenPadding,
                    end = EdgeLayout.screenPadding,
                    top = EdgeLayout.compactGap,
                    bottom = EdgeLayout.sectionGap,
                ),
            verticalArrangement = Arrangement.spacedBy(EdgeLayout.sectionGap),
        ) {
            Nameplate(
                namespace = namespace,
                data = ready,
                onAsk = viewModel::askAboutAsset,
                onAddObservation = viewModel::openObservationComposer,
            )

            when (data) {
                LoadableState.Loading -> EdgeLoadingState("Loading records…")
                is LoadableState.Failed -> EdgeErrorState(data.message, onRetry = viewModel::refresh)
                is LoadableState.Ready -> {
                    val value = data.value
                    if (state.composer.open || state.composer.createdMessage != null) {
                        ComposerCard(namespace, state.composer, viewModel)
                    }
                    if (value.conflicts.isNotEmpty()) {
                        ConflictSection(value.conflicts, now, onOpen = viewModel::openConflict)
                    }
                    if (value.records.isEmpty()) {
                        if (state.composer.createdMessage == null && !state.composer.open) {
                            EdgeEmptyState(
                                title = "No records for ${namespace.uppercase()} yet",
                                message = "Log what you saw or what you fixed and it shows up here.",
                                modifier = Modifier.testTag(AssetUiTags.EMPTY),
                                action = {
                                    TonalPill(text = "Add first record", onClick = viewModel::openComposer)
                                },
                            )
                        }
                    } else {
                        LatestSection(
                            data = value,
                            nowMillis = now,
                            onOpenRecord = viewModel::openRecord,
                            onAddObservation = viewModel::openObservationComposer,
                            onLogMaintenance = viewModel::openMaintenanceComposer,
                            onAddEvent = viewModel::openEventComposer,
                            onAddProcedure = viewModel::openProcedureComposer,
                        )
                        RecordsSection(
                            data = value,
                            records = state.visibleRecords,
                            selected = state.categoryFilter,
                            nowMillis = now,
                            onFilter = viewModel::setCategoryFilter,
                            onRefresh = viewModel::refresh,
                            onOpen = viewModel::openRecord,
                            onOpenConflict = viewModel::openConflict,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Nameplate(
    namespace: String,
    data: MachineDetailData?,
    onAsk: () -> Unit,
    onAddObservation: () -> Unit,
) {
    val tag = namespace.uppercase()
    Column {
        Text(
            text = tag,
            style = EdgeType.nameplate,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val description = data?.asset?.representativeTitle?.let { describe(it, namespace) }
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (data != null) {
            // Same truthful mapping as the machine list. Connectivity is shown
            // by the shell header, so this reads the queue only.
            val status = MachinesAssetStatus.of(
                data.asset.copy(pendingSyncCount = data.pendingSyncRecords),
                SyncSummary(),
                isOnline = true,
            )
            Row(
                modifier = Modifier.padding(top = EdgeDimens.spacingM),
                horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingL),
            ) {
                StatusDot(status = status.status, label = status.label)
                if (data.failedSyncRecords > 0) {
                    StatusDot(status = EdgeStatus.CRITICAL, label = "${data.failedSyncRecords} failed to sync")
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = EdgeDimens.spacingL),
            horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
        ) {
            PillButton(
                text = "Ask about $tag",
                onClick = onAsk,
                modifier = Modifier.testTag("edge-ask-about-asset"),
            )
            TonalPill(
                text = "Log observation",
                onClick = onAddObservation,
                modifier = Modifier.testTag("edge-asset-add"),
            )
        }
    }
}

/** The newest record of each kind: "when was it last serviced?" at a glance. */
@Composable
private fun LatestSection(
    data: MachineDetailData,
    nowMillis: Long,
    onOpenRecord: (String) -> Unit,
    onAddObservation: () -> Unit,
    onLogMaintenance: () -> Unit,
    onAddEvent: () -> Unit,
    onAddProcedure: () -> Unit,
) {
    val rows = listOf(
        LatestRow(AssetRecordCategory.MAINTENANCE, data.maintenanceRecords, AssetUiTags.MAINTENANCE, AssetUiTags.LOG_MAINTENANCE, onLogMaintenance),
        LatestRow(AssetRecordCategory.OBSERVATIONS, data.observationRecords, AssetUiTags.OBSERVATIONS, AssetUiTags.ADD_OBSERVATION, onAddObservation),
        LatestRow(AssetRecordCategory.INCIDENTS, data.incidentRecords, AssetUiTags.INCIDENTS, AssetUiTags.ADD_EVENT, onAddEvent),
        LatestRow(AssetRecordCategory.PROCEDURES, data.procedureRecords, AssetUiTags.PROCEDURES, AssetUiTags.ADD_PROCEDURE, onAddProcedure),
        LatestRow(AssetRecordCategory.DOCUMENTS, data.documentRecords, AssetUiTags.DOCUMENTS, null, null),
    )
    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        SectionHeader("Latest")
        EdgeListGroup(rows) { row ->
            val latest = row.records.firstOrNull()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag(row.sectionTag),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (latest != null) {
                                Modifier
                                    .testTag("${AssetUiTags.SECTION_ITEM_PREFIX}${row.sectionTag.substringAfterLast('-')}-${latest.memoryId}")
                                    .clickable { onOpenRecord(latest.memoryId) }
                            } else {
                                Modifier
                            },
                        )
                        .padding(
                            start = EdgeDimens.spacingL,
                            end = if (row.onAdd == null) EdgeDimens.spacingL else 0.dp,
                            top = 10.dp,
                            bottom = 10.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS)) {
                        Text(
                            text = row.category.singular,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (latest != null) {
                            Text(
                                text = recordTimeLabel(latest.updatedAt, nowMillis),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = latest?.title?.ifBlank { latest.memoryId.take(8) } ?: "None yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (latest != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (row.onAdd != null && row.actionTag != null) {
                    TextButton(
                        onClick = row.onAdd,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .testTag(row.actionTag)
                            .semantics { contentDescription = "Add ${row.category.singular.lowercase()}" },
                    ) {
                        Text("Add", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

private class LatestRow(
    val category: AssetRecordCategory,
    val records: List<Memory>,
    val sectionTag: String,
    val actionTag: String?,
    val onAdd: (() -> Unit)?,
)

@Composable
private fun RecordsSection(
    data: MachineDetailData,
    records: List<Memory>,
    selected: AssetRecordCategory?,
    nowMillis: Long,
    onFilter: (AssetRecordCategory?) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenConflict: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
        val total = data.records.size
        SectionHeader(
            title = "Records",
            subtitle = (if (total == 1) "1 record" else "$total records") + ", newest first",
            trailing = {
                TextButton(onClick = onRefresh, modifier = Modifier.testTag(AssetUiTags.REFRESH)) {
                    Text("Refresh", style = MaterialTheme.typography.labelLarge)
                }
            },
        )
        // Counts by type double as filters over the records already loaded.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AssetUiTags.OVERVIEW)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
        ) {
            FilterChip(
                label = "All",
                count = total,
                selected = selected == null,
                onClick = { onFilter(null) },
                modifier = Modifier.testTag("${AssetUiTags.FILTER_PREFIX}all"),
            )
            data.countsByCategory.forEach { (category, count) ->
                FilterChip(
                    label = category.label,
                    count = count,
                    selected = selected == category,
                    onClick = { onFilter(if (selected == category) null else category) },
                    modifier = Modifier.testTag("${AssetUiTags.FILTER_PREFIX}${category.name.lowercase()}"),
                )
            }
        }
        Column(Modifier.fillMaxWidth().testTag(AssetUiTags.TIMELINE)) {
            if (records.isEmpty()) {
                Text(
                    text = "Nothing of this type yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                EdgeListGroup(records) { record ->
                    RecordRow(
                        record = record,
                        conflicts = data.conflictsFor(record.memoryId),
                        nowMillis = nowMillis,
                        onOpen = { onOpen(record.memoryId) },
                        onOpenConflict = onOpenConflict,
                    )
                }
            }
        }
    }
}

/** Squared, tappable count: "Maintenance 3". Iris only when selected. */
@Composable
private fun FilterChip(
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) scheme.primaryContainer else scheme.surface,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (count != null) {
                Text(
                    text = count.toString(),
                    style = EdgeType.numeric,
                    color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecordRow(
    record: Memory,
    conflicts: List<Conflict>,
    nowMillis: Long,
    onOpen: () -> Unit,
    onOpenConflict: (String) -> Unit,
) {
    val type = AssetModel.categoryOf(record.type).singular
    val time = recordTimeLabel(record.updatedAt, nowMillis)
    val sync = recordSyncStatus(record.syncState, long = false)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${AssetUiTags.TIMELINE_ITEM_PREFIX}${record.memoryId}")
            .semantics {
                contentDescription = "$type: ${record.title}, $time, ${sync.label}. Open record."
            }
            .clickable(onClick = onOpen)
            .padding(horizontal = EdgeDimens.spacingL, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = record.title.ifBlank { record.memoryId.take(8) },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingM)) {
                Text(type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(
            modifier = Modifier.padding(start = EdgeDimens.spacingM, top = 4.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (record.syncState != MemorySyncState.SYNCED) {
                StatusDot(status = sync.status, label = sync.label)
            }
            conflicts.firstOrNull()?.let { conflict ->
                StatusDot(
                    status = EdgeStatus.WARNING,
                    label = if (conflicts.size == 1) "Conflict" else "${conflicts.size} conflicts",
                    modifier = Modifier
                        .testTag("${AssetUiTags.RECORD_CONFLICT_PREFIX}${record.memoryId}")
                        .semantics { contentDescription = "Open conflict ${conflict.conflictId}" }
                        .clickable { onOpenConflict(conflict.conflictId) },
                )
            }
        }
    }
}

/** Real record sync state as a status + human label. */
internal data class RecordSync(val status: EdgeStatus, val label: String)

internal fun recordSyncStatus(state: MemorySyncState, long: Boolean): RecordSync = when (state) {
    MemorySyncState.PENDING -> RecordSync(EdgeStatus.WARNING, if (long) "Queued to sync" else "Queued")
    MemorySyncState.FAILED -> RecordSync(EdgeStatus.CRITICAL, "Sync failed")
    MemorySyncState.SYNCED -> RecordSync(EdgeStatus.SYNCED, "Synced")
    MemorySyncState.LOCAL -> RecordSync(EdgeStatus.OFFLINE, if (long) "Only on this device" else "Device only")
}

@Composable
private fun ConflictSection(
    conflicts: List<Conflict>,
    nowMillis: Long,
    onOpen: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(AssetUiTags.CONFLICTS),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
    ) {
        SectionHeader(
            title = if (conflicts.size == 1) "1 conflict to review" else "${conflicts.size} conflicts to review",
            subtitle = "The cloud has a different version. Pick the one to keep.",
        )
        EdgeListGroup(conflicts) { conflict ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("${AssetUiTags.CONFLICT_PREFIX}${conflict.conflictId}")
                    .semantics {
                        contentDescription = "Conflict ${conflict.subjectKey}: open evidence and resolution"
                    }
                    .clickable { onOpen(conflict.conflictId) }
                    .padding(horizontal = EdgeDimens.spacingL, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = conflict.subjectKey.ifBlank { conflict.conflictId.take(8) },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "This device v${conflict.localVersion ?: "?"}, cloud v${conflict.incomingVersion ?: "?"}" +
                            conflict.incomingAuthority?.let { " from $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = recordTimeLabel(conflict.detectedAt, nowMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = EdgeDimens.spacingM, top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun ComposerCard(
    namespace: String,
    composer: AssetComposerState,
    viewModel: MachineDetailViewModel,
) {
    EdgeCard(modifier = Modifier.fillMaxWidth().testTag(AssetUiTags.COMPOSER)) {
        // Closed-but-confirmed fallback: if the form was dismissed after a
        // save, keep the confirmation and a way to add more.
        if (composer.createdMessage != null && !composer.open) {
            ConfirmationStrip(composer)
            Spacer(Modifier.height(EdgeDimens.spacingM))
            TonalPill(
                text = "Add another",
                onClick = when (composer.type) {
                    MemoryType.REPAIR -> viewModel::openMaintenanceComposer
                    MemoryType.EVENT -> viewModel::openEventComposer
                    MemoryType.PROCEDURE -> viewModel::openProcedureComposer
                    else -> viewModel::openObservationComposer
                },
            )
            return@EdgeCard
        }

        if (composer.createdMessage != null) {
            ConfirmationStrip(composer)
            Spacer(Modifier.height(EdgeDimens.spacingL))
        }

        Text(
            text = "New record for ${namespace.uppercase()}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(EdgeDimens.spacingM))
        // Observations, maintenance, incidents and procedures all go to the
        // SAME machine through the existing CreateMemoryUseCase path.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
        ) {
            composer.assetRecordTypes.forEach { type ->
                val label = AssetModel.categoryOf(type).singular
                FilterChip(
                    label = label,
                    count = null,
                    selected = composer.type == type,
                    onClick = { viewModel.onComposerTypeChange(type) },
                    modifier = Modifier
                        .testTag("${AssetUiTags.TYPE_PREFIX}${type.name.lowercase()}")
                        .semantics { contentDescription = "Record type $label" },
                )
            }
        }
        Spacer(Modifier.height(EdgeDimens.spacingS))
        OutlinedTextField(
            value = composer.title,
            onValueChange = viewModel::onComposerTitleChange,
            label = { Text("Title") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(EdgeDimens.pillRadius),
            modifier = Modifier.fillMaxWidth().testTag(AssetUiTags.COMPOSER_TITLE),
        )
        Spacer(Modifier.height(EdgeLayout.cardGap))
        OutlinedTextField(
            value = composer.content,
            onValueChange = viewModel::onComposerContentChange,
            label = { Text("What happened") },
            placeholder = { Text("What you saw, did or replaced") },
            minLines = 3,
            maxLines = 8,
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(EdgeDimens.pillRadius),
            modifier = Modifier.fillMaxWidth().testTag(AssetUiTags.COMPOSER_CONTENT),
        )
        Spacer(Modifier.height(EdgeDimens.spacingL))
        Text(
            "Sharing",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
        ) {
            FilterChip("Follow policy", null, composer.syncChoice == null, { viewModel.onComposerSyncChoice(null) }, Modifier.testTag("edge-asset-sync-auto"))
            FilterChip("Sync", null, composer.syncChoice == SyncDecision.SYNC, { viewModel.onComposerSyncChoice(SyncDecision.SYNC) }, Modifier.testTag("edge-asset-sync-sync"))
            FilterChip("Only this device", null, composer.syncChoice == SyncDecision.LOCAL_ONLY, { viewModel.onComposerSyncChoice(SyncDecision.LOCAL_ONLY) }, Modifier.testTag("edge-asset-sync-local"))
        }
        composer.error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = EdgeDimens.spacingS),
            )
        }
        Spacer(Modifier.height(EdgeDimens.spacingM))
        Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
            PillButton(
                text = if (composer.submitting) "Saving…" else "Save record",
                onClick = viewModel::submitComposer,
                enabled = !composer.submitting &&
                    (composer.title.isNotBlank() || composer.content.isNotBlank()),
                modifier = Modifier.testTag(AssetUiTags.COMPOSER_SUBMIT),
            )
            TonalPill(text = "Cancel", onClick = viewModel::closeComposer)
        }
    }
}

/** Post-save confirmation: the message plus the record's REAL sync state. */
@Composable
private fun ConfirmationStrip(composer: AssetComposerState) {
    EdgeCardSecondary(
        modifier = Modifier.fillMaxWidth().testTag(AssetUiTags.CREATED_MESSAGE),
    ) {
        Text(
            text = composer.createdMessage.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        composer.createdSyncState?.let { state ->
            val sync = recordSyncStatus(state, long = true)
            StatusDot(
                status = sync.status,
                label = sync.label,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .testTag(AssetUiTags.CREATED_SYNC),
            )
        }
    }
}
