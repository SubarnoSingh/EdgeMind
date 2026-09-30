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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.presentation.components.EdgeCard
import com.example.EdgeMemo.presentation.components.EdgeDimens
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.SolidColor
import com.example.EdgeMemo.presentation.components.EdgeCardSecondary
import com.example.EdgeMemo.presentation.components.EdgeEmptyState
import com.example.EdgeMemo.presentation.components.EdgeErrorState
import com.example.EdgeMemo.presentation.components.EdgeLoadingState
import com.example.EdgeMemo.presentation.components.MetricTile
import com.example.EdgeMemo.presentation.components.PillButton
import com.example.EdgeMemo.presentation.components.StatusChip
import com.example.EdgeMemo.presentation.components.TechLabel
import com.example.EdgeMemo.presentation.components.TonalPill
import com.example.EdgeMemo.presentation.components.relativeTimeLabel
import com.example.EdgeMemo.presentation.machines.AssetModel.AssetRecordCategory
import com.example.EdgeMemo.presentation.shell.LoadableState
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus
import com.example.EdgeMemo.ui.theme.EdgeType
import com.example.EdgeMemo.ui.theme.statusColor

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
    const val LOG_MAINTENANCE = "edge-asset-log-maintenance"
    const val COMPOSER = "edge-asset-composer"
    const val COMPOSER_TITLE = "edge-asset-composer-title"
    const val COMPOSER_CONTENT = "edge-asset-composer-content"
    const val COMPOSER_SUBMIT = "edge-asset-composer-submit"
    const val CREATED_MESSAGE = "edge-asset-created"
    const val CREATED_SYNC = "edge-asset-created-sync"
    const val EMPTY = "edge-asset-empty"
}

/**
 * The asset workspace: "what do we actually know about this asset?".
 *
 * Everything rendered here is derived from REAL stored records for the
 * subject namespace (UI Phase 1 derivation) — no invented machine
 * attributes. Operational status / criticality / telemetry are NOT shown
 * because the active domain does not carry them; the disclosure line says so
 * explicitly.
 */
@Composable
fun MachineDetailScreen(
    viewModel: MachineDetailViewModel,
    namespace: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    val state by viewModel.uiState.collectAsState()
    // Re-read the durable store whenever this screen (re)enters composition —
    // e.g. returning after resolving a conflict in the workflow, so resolved
    // rows disappear and counts reflect the REAL state.
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }
    val data = state.data

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(AssetUiTags.SCREEN),
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .testTag(com.example.EdgeMemo.presentation.components.EdgeUiTags.MACHINE_DETAIL)
                .padding(horizontal = EdgeLayout.screenPadding, vertical = EdgeLayout.cardGap),
            verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
        ) {
            AssetHeader(
                namespace = namespace,
                representativeTitle = (data as? LoadableState.Ready)?.value?.asset
                    ?.representativeTitle ?: namespace,
                recordCount = (data as? LoadableState.Ready)?.value?.asset?.recordCount ?: 0,
                onBack = onBack,
                onRefresh = viewModel::refresh,
                onAsk = viewModel::askAboutAsset,
                onAddObservation = viewModel::openObservationComposer,
            )

            when (val loaded = data) {
                LoadableState.Loading -> EdgeLoadingState("Reading asset knowledge\u2026")
                is LoadableState.Failed -> EdgeErrorState(loaded.message, onRetry = viewModel::refresh)
                is LoadableState.Ready -> {
                    if (state.composer.open || state.composer.createdMessage != null) {
                        ComposerCard(namespace, state.composer, viewModel)
                    }
                    if (loaded.value.records.isEmpty() &&
                        state.composer.createdMessage == null && !state.composer.open
                    ) {
                        EdgeEmptyState(
                            title = "No records for this asset yet",
                            message = "Nothing in local memory references subject " +
                                "\u201C${namespace}\u201D. Add an observation or sync " +
                                "knowledge to populate this workspace.",
                            modifier = Modifier.testTag(AssetUiTags.EMPTY),
                        )
                        TonalPill(
                            text = "Add first record",
                            onClick = viewModel::openComposer,
                        )
                    } else {
                        if (loaded.value.records.isNotEmpty()) {
                            OverviewSection(
                                loaded.value,
                                onFilter = viewModel::setCategoryFilter,
                                selected = state.categoryFilter,
                            )
                            TimelineSection(
                                data = loaded.value,
                                records = state.visibleRecords,
                                nowMillis = nowMillis(),
                                onOpen = viewModel::openRecord,
                                onOpenConflict = viewModel::openConflict,
                            )
                            FocusedActivitySections(
                                data = loaded.value,
                                nowMillis = nowMillis(),
                                onOpenRecord = viewModel::openRecord,
                                onOpenConflict = viewModel::openConflict,
                                onAddObservation = viewModel::openObservationComposer,
                                onLogMaintenance = viewModel::openMaintenanceComposer,
                            )
                        }
                    }
                    if (loaded.value.conflicts.isNotEmpty()) {
                        ConflictSection(
                            loaded.value.conflicts,
                            nowMillis(),
                            onOpen = viewModel::openConflict,
                        )
                    }
                    DomainDisclosure()
                }
            }
        }
    }
}

@Composable
private fun AssetHeader(
    namespace: String,
    representativeTitle: String,
    recordCount: Int,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAsk: () -> Unit,
    onAddObservation: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "\u2190 Back",
                style = EdgeType.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag("edge-detail-back")
                    .semantics { contentDescription = "Back to machines" }
                    .clickable(onClick = onBack)
                    .padding(EdgeLayout.compactGap),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "Refresh",
                style = EdgeType.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag(AssetUiTags.REFRESH)
                    .clickable(onClick = onRefresh)
                    .padding(EdgeLayout.compactGap),
            )
        }
        TechLabel(text = "Asset")
        Text(
            text = namespace.uppercase(),
            style = EdgeType.screenTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (representativeTitle.isNotBlank() && !representativeTitle.equals(namespace, ignoreCase = true)) {
            Text(
                text = representativeTitle,
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap)) {
            PillButton(text = "Ask about this asset", onClick = onAsk, modifier = Modifier.testTag("edge-ask-about-asset"))
            TonalPill(
                text = "+ Add observation",
                onClick = onAddObservation,
                modifier = Modifier.testTag("edge-asset-add"),
            )
        }
    }
}

@Composable
private fun OverviewSection(
    data: MachineDetailData,
    onFilter: (AssetRecordCategory?) -> Unit,
    selected: AssetRecordCategory?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AssetUiTags.OVERVIEW),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
    ) {
        TechLabel(text = "WHAT HAPPENED?")
        Text(
            text = "${data.records.size} records \u00B7 last activity " +
                "${relativeTimeLabel(data.asset.lastActivityAt)}. " +
                "Counts are derived from records currently available in local knowledge.",
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
            MetricTile(
                label = "Total records",
                value = data.records.size.toString(),
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Maintenance",
                value = data.maintenanceRecords.size.toString(),
                supportingLine = "repair records",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
            MetricTile(
                label = "Observations",
                value = data.observationRecords.size.toString(),
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Incidents",
                value = data.incidentRecords.size.toString(),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap)) {
            MetricTile(
                label = "Procedures",
                value = data.procedureRecords.size.toString(),
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Documents",
                value = data.documentRecords.size.toString(),
                modifier = Modifier.weight(1f),
            )
        }
        EdgeCardSecondary {
            Row(horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap)) {
                StatusChip(
                    text = "${data.pendingSyncRecords} pending sync",
                    color = if (data.pendingSyncRecords > 0) {
                        EdgeStatus.WARNING.statusColor()
                    } else {
                        EdgeStatus.NEUTRAL.statusColor()
                    },
                )
                StatusChip(
                    text = "${data.unresolvedConflictCount} unresolved",
                    color = if (data.unresolvedConflictCount > 0L) {
                        EdgeStatus.WARNING.statusColor()
                    } else {
                        EdgeStatus.NEUTRAL.statusColor()
                    },
                )
                if (data.failedSyncRecords > 0) {
                    StatusChip(
                        text = "${data.failedSyncRecords} sync failed",
                        color = EdgeStatus.CRITICAL.statusColor(),
                    )
                }
            }
            Text(
                text = "Counts and sync states come from the loaded records and durable conflict/sync stores; " +
                    "no condition, health or risk score is inferred.",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TechLabel(text = "FILTER RECENT ACTIVITY", color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Presentation-only filtering over the records already loaded for this asset.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
        ) {
            FilterChip(
                label = "All",
                selected = selected == null,
                onClick = { onFilter(null) },
                modifier = Modifier.testTag("${AssetUiTags.FILTER_PREFIX}all"),
            )
            data.countsByCategory.forEach { (category, count) ->
                FilterChip(
                    label = "${category.label} ($count)",
                    selected = selected == category,
                    onClick = { onFilter(if (selected == category) null else category) },
                    modifier = Modifier.testTag("${AssetUiTags.FILTER_PREFIX}${category.name.lowercase()}"),
                )
            }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StatusChip(
        text = label,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        containerColor = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        showDot = false,
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun TimelineSection(
    data: MachineDetailData,
    records: List<Memory>,
    nowMillis: Long,
    onOpen: (String) -> Unit,
    onOpenConflict: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(AssetUiTags.TIMELINE),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
    ) {
        TechLabel(text = "RECENT ACTIVITY \u00B7 NEWEST FIRST")
        Text(
            text = "The filter applies to this already-loaded local activity set only.",
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (records.isEmpty()) {
            Text(
                text = "No records in this category are available in local knowledge.",
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        records.forEach { record ->
            TimelineEntry(
                record = record,
                conflicts = data.conflictsFor(record.memoryId),
                nowMillis = nowMillis,
                tag = "${AssetUiTags.TIMELINE_ITEM_PREFIX}${record.memoryId}",
                conflictTag = "${AssetUiTags.RECORD_CONFLICT_PREFIX}${record.memoryId}",
                onOpen = { onOpen(record.memoryId) },
                onOpenConflict = onOpenConflict,
            )
        }
    }
}

@Composable
private fun FocusedActivitySections(
    data: MachineDetailData,
    nowMillis: Long,
    onOpenRecord: (String) -> Unit,
    onOpenConflict: (String) -> Unit,
    onAddObservation: () -> Unit,
    onLogMaintenance: () -> Unit,
) {
    FocusedRecordSection(
        title = "RECENT MAINTENANCE",
        subtitle = "What was done",
        records = data.maintenanceRecords,
        emptyMessage = "No maintenance records available in local knowledge.",
        sectionTag = AssetUiTags.MAINTENANCE,
        actionLabel = "Log maintenance",
        actionTag = AssetUiTags.LOG_MAINTENANCE,
        onAction = onLogMaintenance,
        data = data,
        nowMillis = nowMillis,
        onOpenRecord = onOpenRecord,
        onOpenConflict = onOpenConflict,
    )
    FocusedRecordSection(
        title = "OBSERVATIONS",
        subtitle = "What technicians recorded",
        records = data.observationRecords,
        emptyMessage = "No observations are available in local knowledge.",
        sectionTag = AssetUiTags.OBSERVATIONS,
        actionLabel = "+ Add observation",
        actionTag = AssetUiTags.ADD_OBSERVATION,
        onAction = onAddObservation,
        data = data,
        nowMillis = nowMillis,
        onOpenRecord = onOpenRecord,
        onOpenConflict = onOpenConflict,
    )
    FocusedRecordSection(
        title = "INCIDENTS",
        subtitle = "Recorded events and failures",
        records = data.incidentRecords,
        emptyMessage = "No incident records are available in local knowledge.",
        sectionTag = AssetUiTags.INCIDENTS,
        data = data,
        nowMillis = nowMillis,
        onOpenRecord = onOpenRecord,
        onOpenConflict = onOpenConflict,
    )
    FocusedRecordSection(
        title = "PROCEDURES",
        subtitle = "Stored procedural evidence to inspect before acting",
        records = data.procedureRecords,
        emptyMessage = "No procedures are available in local knowledge.",
        sectionTag = AssetUiTags.PROCEDURES,
        data = data,
        nowMillis = nowMillis,
        onOpenRecord = onOpenRecord,
        onOpenConflict = onOpenConflict,
    )
    FocusedRecordSection(
        title = "DOCUMENTS",
        subtitle = "Manuals and other stored knowledge",
        records = data.documentRecords,
        emptyMessage = "No documents are available in local knowledge.",
        sectionTag = AssetUiTags.DOCUMENTS,
        data = data,
        nowMillis = nowMillis,
        onOpenRecord = onOpenRecord,
        onOpenConflict = onOpenConflict,
    )
}

@Composable
private fun FocusedRecordSection(
    title: String,
    subtitle: String,
    records: List<Memory>,
    emptyMessage: String,
    sectionTag: String,
    data: MachineDetailData,
    nowMillis: Long,
    onOpenRecord: (String) -> Unit,
    onOpenConflict: (String) -> Unit,
    actionLabel: String? = null,
    actionTag: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(sectionTag),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TechLabel(text = title)
                Text(
                    text = subtitle,
                    style = EdgeType.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (actionLabel != null && onAction != null) {
                TonalPill(
                    text = actionLabel,
                    onClick = onAction,
                    modifier = if (actionTag != null) Modifier.testTag(actionTag) else Modifier,
                )
            }
        }
        if (records.isEmpty()) {
            EdgeCardSecondary {
                Text(
                    text = emptyMessage,
                    style = EdgeType.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            records.take(FOCUSED_SECTION_LIMIT).forEach { record ->
                TimelineEntry(
                    record = record,
                    conflicts = data.conflictsFor(record.memoryId),
                    nowMillis = nowMillis,
                    tag = "${AssetUiTags.SECTION_ITEM_PREFIX}${sectionTag.substringAfterLast('-')}-${record.memoryId}",
                    conflictTag = "${AssetUiTags.RECORD_CONFLICT_PREFIX}${sectionTag.substringAfterLast('-')}-${record.memoryId}",
                    onOpen = { onOpenRecord(record.memoryId) },
                    onOpenConflict = onOpenConflict,
                )
            }
            if (records.size > FOCUSED_SECTION_LIMIT) {
                Text(
                    text = "Showing the ${FOCUSED_SECTION_LIMIT} most recent of ${records.size} local records. " +
                        "Use the activity filter above to inspect the loaded category.",
                    style = EdgeType.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TimelineEntry(
    record: Memory,
    conflicts: List<Conflict>,
    nowMillis: Long,
    tag: String,
    conflictTag: String,
    onOpen: () -> Unit,
    onOpenConflict: (String) -> Unit,
) {
    val timestamp = formatRecordTimestamp(record.updatedAt)
    EdgeCardSecondary(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics {
                contentDescription = "${AssetModel.categoryOf(record.type).label}: ${record.title}, " +
                    "$timestamp. Open record detail."
            }
            .clickable(onClick = onOpen),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                StatusChip(
                    text = AssetModel.categoryOf(record.type).label.uppercase(),
                    color = MaterialTheme.colorScheme.primary,
                    showDot = true,
                )
                Text(
                    text = record.title.ifBlank { record.memoryId.take(8) },
                    style = EdgeType.bodyEmphasis,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = record.content,
                    style = EdgeType.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(EdgeLayout.compactGap))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = timestamp,
                    style = EdgeType.numeric,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = relativeTimeLabel(record.updatedAt, nowMillis),
                    style = EdgeType.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SyncIndicator(record.syncState)
                conflicts.firstOrNull()?.let { conflict ->
                    StatusChip(
                        text = if (conflicts.size == 1) "CONFLICT" else "${conflicts.size} CONFLICTS",
                        color = EdgeStatus.WARNING.statusColor(),
                        showDot = true,
                        modifier = Modifier
                            .testTag(conflictTag)
                            .semantics {
                                contentDescription = "Open conflict ${conflict.conflictId}"
                            }
                            .clickable { onOpenConflict(conflict.conflictId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncIndicator(state: MemorySyncState, modifier: Modifier = Modifier) {
    val status = when (state) {
        MemorySyncState.PENDING -> EdgeStatus.WARNING
        MemorySyncState.FAILED -> EdgeStatus.CRITICAL
        MemorySyncState.SYNCED -> EdgeStatus.SYNCED
        MemorySyncState.LOCAL -> EdgeStatus.NEUTRAL
    }
    StatusChip(
        text = when (state) {
            MemorySyncState.PENDING -> "pending"
            MemorySyncState.FAILED -> "failed"
            MemorySyncState.SYNCED -> "synced"
            MemorySyncState.LOCAL -> "local only"
        },
        color = status.statusColor(),
        showDot = true,
        modifier = modifier,
    )
}

@Composable
private fun ConflictSection(
    conflicts: List<Conflict>,
    nowMillis: Long,
    onOpen: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(AssetUiTags.CONFLICTS),
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
    ) {
        TechLabel(
            text = "UNRESOLVED CONFLICTS \u00B7 ${conflicts.size}",
            color = EdgeStatus.WARNING.statusColor(),
        )
        conflicts.forEach { conflict ->
            EdgeCardSecondary(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("${AssetUiTags.CONFLICT_PREFIX}${conflict.conflictId}")
                    .semantics {
                        contentDescription = "Conflict ${conflict.subjectKey}: open evidence and resolution"
                    }
                    .clickable { onOpen(conflict.conflictId) },
            ) {
                Text(
                    text = conflict.subjectKey.ifBlank { conflict.conflictId.take(8) },
                    style = EdgeType.bodyEmphasis,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "local v${conflict.localVersion ?: "?"} \u00B7 " +
                        "${conflict.localOrigin.lowercase()} vs cloud v${conflict.incomingVersion ?: "?"} \u00B7 " +
                        "${conflict.incomingOrigin.lowercase()}" +
                        conflict.incomingAuthority?.let { " \u00B7 authority $it" }.orEmpty(),
                    style = EdgeType.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${conflict.reason} \u00B7 detected ${relativeTimeLabel(conflict.detectedAt, nowMillis)}",
                    style = EdgeType.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Honest domain disclosure: the fields a maintenance engineer would expect
 * on a typed machine record are NOT invented; they appear when the machine
 * domain API lands. Kept verbatim from UI Phase 1 (pinned by shell tests).
 */
@Composable
private fun DomainDisclosure() {
    EdgeCard {
        TechLabel(text = "DOMAIN NOTES")
        Spacer(Modifier.size(EdgeLayout.compactGap))
        Text(
            text = "Operational status and criticality are not exposed by the local domain yet \u2014 " +
                "shown here once the machine data API lands.",
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Everything above is derived from real stored records for this subject " +
                "namespace. This view browses fully offline from the local Qdrant memory.",
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ComposerCard(
    namespace: String,
    composer: AssetComposerState,
    viewModel: MachineDetailViewModel,
) {
    EdgeCard(modifier = Modifier.fillMaxWidth().testTag(AssetUiTags.COMPOSER)) {
        if (composer.createdMessage != null && !composer.open) {
            Text(
                text = composer.createdMessage,
                style = EdgeType.body,
                color = EdgeStatus.SYNCED.statusColor(),
                modifier = Modifier.testTag(AssetUiTags.CREATED_MESSAGE),
            )
            composer.createdSyncState?.let { state ->
                Spacer(Modifier.size(EdgeLayout.compactGap))
                SyncIndicator(
                    state = state,
                    modifier = Modifier.testTag(AssetUiTags.CREATED_SYNC),
                )
            }
            Spacer(Modifier.size(EdgeLayout.compactGap))
            TonalPill(
                text = "Add another",
                onClick = if (composer.type == MemoryType.REPAIR) {
                    viewModel::openMaintenanceComposer
                } else {
                    viewModel::openObservationComposer
                },
            )
            return@EdgeCard
        }
        TechLabel(
            text = if (composer.type == MemoryType.REPAIR) {
                "LOG MAINTENANCE \u00B7 ${namespace.uppercase()}"
            } else {
                "ADD ${composer.type.name} \u00B7 ${namespace.uppercase()}"
            },
        )
        Spacer(Modifier.size(EdgeLayout.compactGap))
        LabeledField(
            label = "TITLE",
            value = composer.title,
            placeholder = "Short title",
            tag = AssetUiTags.COMPOSER_TITLE,
            onValueChange = viewModel::onComposerTitleChange,
        )
        Spacer(Modifier.size(EdgeLayout.compactGap))
        LabeledField(
            label = "DETAIL",
            value = composer.content,
            placeholder = "What was observed, performed or replaced\u2026",
            tag = AssetUiTags.COMPOSER_CONTENT,
            singleLine = false,
            onValueChange = viewModel::onComposerContentChange,
        )
        Spacer(Modifier.size(EdgeLayout.compactGap))
        Text("TYPE", style = EdgeType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        StatusChip(
            text = composer.type.name,
            color = MaterialTheme.colorScheme.primary,
            showDot = true,
            modifier = Modifier.testTag("edge-asset-type-${composer.type.name.lowercase()}"),
        )
        Spacer(Modifier.size(EdgeLayout.compactGap))
        Text("SYNC", style = EdgeType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
        ) {
            SyncChip("auto (policy)", composer.syncChoice == null, { viewModel.onComposerSyncChoice(null) }, "edge-asset-sync-auto")
            SyncChip("sync", composer.syncChoice == SyncDecision.SYNC, { viewModel.onComposerSyncChoice(SyncDecision.SYNC) }, "edge-asset-sync-sync")
            SyncChip("local only", composer.syncChoice == SyncDecision.LOCAL_ONLY, { viewModel.onComposerSyncChoice(SyncDecision.LOCAL_ONLY) }, "edge-asset-sync-local")
        }
        Spacer(Modifier.size(EdgeLayout.compactGap))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
        ) {
            PillButton(
                text = if (composer.submitting) "Saving\u2026" else "Save to memory",
                onClick = viewModel::submitComposer,
                enabled = !composer.submitting &&
                    (composer.title.isNotBlank() || composer.content.isNotBlank()),
                modifier = Modifier.testTag(AssetUiTags.COMPOSER_SUBMIT),
            )
            TonalPill(text = "Cancel", onClick = viewModel::closeComposer)
        }
        composer.error?.let {
            Spacer(Modifier.size(EdgeLayout.compactGap))
            Text(
                text = it,
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.error,
            )
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
private fun SyncChip(label: String, selected: Boolean, onClick: () -> Unit, tag: String) {
    FilterChip(label, selected, onClick, modifier = Modifier.testTag(tag))
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
        androidx.compose.foundation.layout.Box(
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
                keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
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

private const val FOCUSED_SECTION_LIMIT = 3
