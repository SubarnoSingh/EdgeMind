package com.example.EdgeMemo.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.EdgeMemo.ui.theme.EdgeLayout
import com.example.EdgeMemo.ui.theme.EdgeStatus
import com.example.EdgeMemo.ui.theme.EdgeType
import com.example.EdgeMemo.ui.theme.statusStyleFor

/** Small colored dot paired with mandatory text — color is never alone. */
@Composable
fun StatusDot(
    status: EdgeStatus,
    label: String,
    modifier: Modifier = Modifier,
) {
    val style = statusStyleFor(status)
    Row(
        modifier = modifier.semantics { contentDescription = "$label: $status" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EdgeLayout.compactGap + 2.dp),
    ) {
        Box(
            Modifier
                .size(EdgeLayout.statusDotSize)
                .background(style.color, CircleShape),
        )
        Text(
            text = label,
            style = EdgeType.label,
            color = style.onContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Compact bordered status badge for header bars: dot + uppercase label,
 * tinted container. Accessibility label combines both.
 */
@Composable
fun EdgeStatusBadge(
    status: EdgeStatus,
    label: String,
    modifier: Modifier = Modifier,
) {
    val style = statusStyleFor(status)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(style.container, RoundedCornerShape(50))
            .border(EdgeLayout.hairline, style.color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .semantics { contentDescription = label }
            .padding(horizontal = EdgeLayout.cardGap, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).background(style.color, CircleShape))
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = style.onContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Dashboard metric tile: monospace value + text label + optional status. */
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supportingLine: String? = null,
    status: EdgeStatus? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    EdgeCardSecondary(modifier = modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = value,
                style = EdgeType.metricValue,
                color = valueColor,
            )
            Spacer(Modifier.weight(1f))
            if (status != null) {
                StatusDot(
                    status = status,
                    label = "",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = EdgeType.label,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (supportingLine != null) {
            Text(
                text = supportingLine,
                style = EdgeType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Bottom application navigation: five destinations, real state only. */
@Composable
fun <T : Any> EdgeBottomNavBar(
    destinations: List<T>,
    selected: T,
    labelOf: (T) -> String,
    iconOf: @Composable (T) -> ImageVector,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(EdgeUiTags.BOTTOM_NAV)
                .height(EdgeLayout.bottomNavHeight)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEach { destination ->
                val isSelected = destination == selected
                val label = labelOf(destination)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = EdgeLayout.minTarget)
                        .clip(RoundedCornerShape(14.dp))
                        .testTag("edge-tab-${label.lowercase().replace(' ', '-')}")
                        .then(
                            if (isSelected) {
                                Modifier.background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(14.dp),
                                )
                            } else {
                                Modifier
                            },
                        )
                        .semantics { contentDescription = "$label tab" }
                        .clickable { onSelect(destination) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = iconOf(destination),
                        contentDescription = null,
                        modifier = Modifier.size(EdgeLayout.indicatorSize + 4.dp),
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
