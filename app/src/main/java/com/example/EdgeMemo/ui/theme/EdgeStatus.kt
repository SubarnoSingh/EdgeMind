package com.example.EdgeMemo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Industrial semantic status palette — the ONE place operational status
 * colors are defined. Every status surface (badges, dots, chips, tiles)
 * resolves through here; composables must not pick literal colors for state.
 *
 * Rules (design system):
 *  - Status is NEVER conveyed by color alone — every consumer pairs the
 *    color with a text label and/or icon (accessibility requirement).
 *  - Dark charcoal is the primary industrial identity; the light theme maps
 *    the same semantics with tuned contrast.
 */
enum class EdgeStatus {
    /** Healthy / available / online. */
    HEALTHY,

    /** Needs attention (pending sync queue, reviewable conflict). */
    WARNING,

    /** Critical: failed operations, destructive state. */
    CRITICAL,

    /** Disconnected from the network (data may be stale, never "broken"). */
    OFFLINE,

    /** A synchronization operation is in flight. */
    SYNCING,

    /** Cloud confirmation present (real ACKED operations). */
    SYNCED,

    /** Neutral/informational (local-only, idle, no activity). */
    NEUTRAL,
}

/** Foreground + container pair so text/dots keep readable contrast. */
data class StatusStyle(
    val color: Color,
    val container: Color,
    val onContainer: Color,
)

/** Resolve the [StatusStyle] for a semantic status in the active theme. */
@Composable
fun statusStyleFor(status: EdgeStatus): StatusStyle {
    val edge = LocalEdgeColors.current
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        EdgeStatus.HEALTHY, EdgeStatus.SYNCED -> StatusStyle(
            color = edge.positive,
            container = edge.positiveContainer,
            onContainer = edge.positive,
        )
        EdgeStatus.WARNING -> StatusStyle(
            color = edge.accentAmber,
            container = edge.accentAmber.copy(alpha = 0.16f),
            onContainer = edge.accentAmber,
        )
        EdgeStatus.CRITICAL -> StatusStyle(
            color = scheme.error,
            container = scheme.errorContainer,
            onContainer = scheme.onErrorContainer,
        )
        EdgeStatus.OFFLINE -> StatusStyle(
            color = edge.accentAmber,
            container = edge.accentAmber.copy(alpha = 0.14f),
            onContainer = edge.accentAmber,
        )
        EdgeStatus.SYNCING -> StatusStyle(
            color = edge.accentBlue,
            container = edge.accentBlue.copy(alpha = 0.16f),
            onContainer = edge.accentBlue,
        )
        EdgeStatus.NEUTRAL -> StatusStyle(
            color = scheme.onSurfaceVariant,
            container = scheme.surfaceVariant,
            onContainer = scheme.onSurfaceVariant,
        )
    }
}

/** Foreground color token for a semantic status (design-system single source). */
@Composable
fun EdgeStatus.statusColor(): Color = statusStyleFor(this).color
