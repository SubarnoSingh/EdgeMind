package com.example.EdgeMemo.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.presentation.components.PillButton
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeType

/** Test tags for UI-state assertions — single source of truth. */
object EdgeUiTags {
    const val LOADING = "edge-loading"
    const val EMPTY = "edge-empty"
    const val ERROR = "edge-error"
    const val RETRY = "edge-retry"
    const val DASHBOARD = "edge-dashboard"
    const val MACHINES = "edge-machines"
    const val MACHINE_DETAIL = "edge-machine-detail"
    const val BOTTOM_NAV = "edge-bottom-nav"
    const val SYNC_BADGE = "edge-sync-badge"
    const val DASHBOARD_SYNC_ROW = "edge-dashboard-sync-row"
    const val CONNECTION_BADGE = "edge-connection-badge"
    const val OPEN_RECORDS = "edge-open-records"
    const val OPEN_CONFLICTS = "edge-open-conflicts"
    const val OPEN_ASK = "edge-open-ask"
    const val MACHINE_CARD_PREFIX = "edge-machine-card-"
    const val MACHINE_CARD_STATUS_PREFIX = "edge-machine-card-status-"
    const val RECENT_CARD_PREFIX = "edge-recent-card-"
}

/** Loading state: explicit, announced, never a blank screen. */
@Composable
fun EdgeLoadingState(
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag(EdgeUiTags.LOADING)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.5.dp,
            )
            Text(
                text = label,
                style = EdgeType.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Honest empty state — shown whenever a real data source legitimately has
 * no entries. Never replaced with fabricated rows.
 */
@Composable
fun EdgeEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag(EdgeUiTags.EMPTY)
            .padding(vertical = 40.dp, horizontal = EdgeLayout.screenPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap),
        ) {
            Text(
                text = title,
                style = EdgeType.sectionTitle,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = EdgeLayout.compactGap, bottom = EdgeLayout.cardGap),
            )
            action?.invoke()
        }
    }
}

/** Error state with honest message + explicit retry action when offered. */
@Composable
fun EdgeErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(EdgeUiTags.ERROR)
            .semantics { contentDescription = "Error: $message" }
            .padding(vertical = 32.dp, horizontal = EdgeLayout.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(EdgeLayout.cardGap),
    ) {
        Text(
            text = "Couldn't read local memory",
            style = EdgeType.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = message,
            style = EdgeType.metadata,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            TonalPill(
                text = "Try again",
                onClick = onRetry,
                modifier = Modifier.testTag(EdgeUiTags.RETRY),
            )
        }
    }
}

/** Full-screen placeholder for destinations completed in later UI phases. */
@Composable
fun EdgePhasePlaceholder(
    feature: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(EdgeLayout.screenPadding),
        contentAlignment = Alignment.Center,
    ) {
        EdgeCard(modifier = Modifier.fillMaxWidth()) {
            Text(feature, style = EdgeType.sectionTitle)
            EdgeSpacer(height = EdgeLayout.compactGap)
            Text(
                detail,
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
