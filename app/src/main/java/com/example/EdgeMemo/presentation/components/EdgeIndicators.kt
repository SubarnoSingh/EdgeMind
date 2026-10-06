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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(EdgeLayout.statusDotSize)
                .background(style.color, CircleShape),
        )
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = EdgeType.label,
                color = style.onContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Compact header badge: dot + short label on a tinted, squared chip. The
 * accessibility label is the full text.
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
            .clip(RoundedCornerShape(8.dp))
            .background(style.container, RoundedCornerShape(8.dp))
            .semantics { contentDescription = label }
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).background(style.color, CircleShape))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = style.onContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Metric readout: a large tabular figure over its label, like a gauge face.
 * A status dot sits beside the number only when the value means something
 * is off (or explicitly fine).
 */
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supportingLine: String? = null,
    status: EdgeStatus? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    EdgeCardSecondary(modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = value,
                style = EdgeType.metricValue,
                color = valueColor,
            )
            if (status != null) {
                StatusDot(status = status, label = "")
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
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

/**
 * Bottom navigation: flat panel with a top hairline. The active tab gets an
 * iris bar above its icon and full-contrast text; inactive tabs stay muted.
 */
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
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(EdgeUiTags.BOTTOM_NAV)
                    .height(EdgeLayout.bottomNavHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                destinations.forEach { destination ->
                    val isSelected = destination == selected
                    val label = labelOf(destination)
                    val tint = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTag("edge-tab-${label.lowercase().replace(' ', '-')}")
                            .semantics { contentDescription = "$label tab" }
                            .clickable { onSelect(destination) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .width(28.dp)
                                .height(2.dp)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp),
                                ),
                        )
                        Spacer(Modifier.height(9.dp))
                        Icon(
                            imageVector = iconOf(destination),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = tint,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = tint,
                        )
                    }
                }
            }
        }
    }
}
