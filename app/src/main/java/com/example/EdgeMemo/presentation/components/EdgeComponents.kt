package com.example.EdgeMemo.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.EdgeMemo.ui.theme.LocalEdgeColors

// ── Dimens / shapes ────────────────────────────────────────────────────────

object EdgeDimens {
    val spacingXs = 4.dp
    val spacingS = 8.dp
    val spacingM = 12.dp
    val spacingL = 16.dp
    val spacingXl = 24.dp
    val spacing2xl = 32.dp
    val cardRadius = 20.dp
    val pillRadius = 28.dp
    val inputRadius = 26.dp
    val minTouch = 44.dp
}

// ── App icons (core icon set has no sun/moon; drawn locally) ───────────────

val SunIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Sun",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(8.6f, 12f)
            arcTo(3.4f, 3.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 15.4f, 12f)
            arcTo(3.4f, 3.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8.6f, 12f)
            close()
        }
        val rays = listOf(
            Pair(12f, 2.2f) to Pair(12f, 4.4f),
            Pair(12f, 19.6f) to Pair(12f, 21.8f),
            Pair(2.2f, 12f) to Pair(4.4f, 12f),
            Pair(19.6f, 12f) to Pair(21.8f, 12f),
            Pair(5.07f, 5.07f) to Pair(6.63f, 6.63f),
            Pair(17.37f, 17.37f) to Pair(18.93f, 18.93f),
            Pair(18.93f, 5.07f) to Pair(17.37f, 6.63f),
            Pair(6.63f, 17.37f) to Pair(5.07f, 18.93f),
        )
        rays.forEach { (from, to) ->
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            ) {
                moveTo(from.first, from.second)
                lineTo(to.first, to.second)
            }
        }
    }.build()
}

val MoonIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Moon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Crescent = outer circle minus a right-offset inner circle (EvenOdd).
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(21f, 12f)
            arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, 12f)
            arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, 21f, 12f)
            close()
            moveTo(23.5f, 10f)
            arcTo(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7.5f, 10f)
            arcTo(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 23.5f, 10f)
            close()
        }
    }.build()
}

// A simple gear, drawn locally because the core icon set has no Settings icon.
val SettingsIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Settings",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        for (i in 0 until 6) {
            group(name = "tooth$i", rotate = 60f * i, pivotX = 12f, pivotY = 12f) {
                path(fill = SolidColor(Color.Black)) {
                    moveTo(10.7f, 1.8f)
                    lineTo(13.3f, 1.8f)
                    lineTo(13.3f, 6.4f)
                    lineTo(10.7f, 6.4f)
                    close()
                }
            }
        }
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(6f, 12f)
            arcTo(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 18f, 12f)
            arcTo(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 6f, 12f)
            close()
            moveTo(14.4f, 12f)
            arcTo(2.4f, 2.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9.6f, 12f)
            arcTo(2.4f, 2.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 14.4f, 12f)
            close()
        }
    }.build()
}

// ── Cards ──────────────────────────────────────────────────────────────────

/**
 * The standard EdgeMind surface: rounded, hairline-bordered, slightly
 * translucent so the ambient background breathes through, with calm
 * elevation. Primary content (answers, capture, memory cards).
 */
@Composable
fun EdgeCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(EdgeDimens.cardRadius),
    containerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    contentPadding: PaddingValues = PaddingValues(EdgeDimens.spacingL),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * Quieter secondary surface: status summaries, metadata sections — clearly
 * one step below [EdgeCard] in the visual hierarchy.
 */
@Composable
fun EdgeCardSecondary(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
    contentPadding: PaddingValues = PaddingValues(EdgeDimens.spacingM),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

// ── Chips / pills ──────────────────────────────────────────────────────────

/** Small rounded status pill with a leading dot. */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    showDot: Boolean = true,
) {
    Row(
        modifier = modifier
            .background(containerColor, RoundedCornerShape(50))
            .padding(horizontal = EdgeDimens.spacingM, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingXs + 2.dp),
    ) {
        if (showDot) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(color.copy(alpha = 0.85f), CircleShape),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Filled pill button — the primary rounded action. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(EdgeDimens.pillRadius),
        color = if (enabled) containerColor else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = EdgeDimens.spacingXl, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Tonal pill for secondary actions. */
@Composable
fun TonalPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    PillButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        icon = icon,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
}

/** Circular icon pill (e.g. the send button). */
@Composable
fun IconPill(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onPrimary,
    containerColor: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) containerColor else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
        }
    }
}

// ── Ask / Memory segmented mode switch ─────────────────────────────────────

enum class EdgeMode(val label: String) {
    ASK("Ask"),
    MEMORY("Memory"),
}

@Composable
fun ModeSwitch(
    selected: EdgeMode,
    onSelect: (EdgeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f), RoundedCornerShape(EdgeDimens.pillRadius))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EdgeMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = 38.dp)
                    .then(
                        if (isSelected) {
                            Modifier
                                .shadow(2.dp, RoundedCornerShape(EdgeDimens.pillRadius))
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(EdgeDimens.pillRadius))
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(EdgeDimens.pillRadius))
                        } else {
                            Modifier
                        },
                    )
                    .semantics {
                        this.contentDescription = mode.label
                        this.selected = isSelected
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(mode) },
                    )
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
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

// ── Top bar ────────────────────────────────────────────────────────────────

/**
 * Lightweight floating header:
 *
 *   [theme]            [ Ask | Memory ]            [settings]
 *              [ EDGE READY / OFFLINE ]
 *
 * Three separate floating controls — NOT one giant capsule — with a small
 * subordinate connectivity chip beneath the centered switch. Every control
 * carries its own subtle surface so it reads in both themes.
 */
@Composable
fun EdgeTopBar(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    mode: EdgeMode,
    onModeChange: (EdgeMode) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    online: Boolean = true,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EdgeDimens.spacingM, vertical = EdgeDimens.spacingS),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FloatingIconButton(
                    onClick = onToggleTheme,
                    imageVector = if (darkTheme) SunIcon else MoonIcon,
                    contentDescription = if (darkTheme) {
                        "Switch to light appearance"
                    } else {
                        "Switch to dark appearance"
                    },
                )
                FloatingIconButton(
                    onClick = onOpenSettings,
                    imageVector = SettingsIcon,
                    contentDescription = "Settings",
                )
            }
            ModeSwitch(
                selected = mode,
                onSelect = onModeChange,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        EdgeStatusChip(online = online, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * Tiny subordinate status chip under the header controls. Driven ONLY by the
 * real connectivity StateFlow — never faked. Green dot + "EDGE READY" while
 * online, amber dot + "OFFLINE" otherwise, matching the app's semantic
 * accent language (amber already means offline/local in the Ask hints).
 */
@Composable
fun EdgeStatusChip(online: Boolean, modifier: Modifier = Modifier) {
    val edgeColors = LocalEdgeColors.current
    val label: String
    val color: Color
    val container: Color
    if (online) {
        label = "EDGE READY"
        color = edgeColors.positive
        container = edgeColors.positiveContainer.copy(alpha = 0.85f)
    } else {
        label = "OFFLINE"
        color = MaterialTheme.colorScheme.tertiary
        container = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f)
    }
    Row(
        modifier = modifier
            .semantics { contentDescription = if (online) "Edge ready, online" else "Offline" }
            .background(container, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .size(5.dp)
                .background(color.copy(alpha = 0.9f), CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.8.sp,
            color = color,
        )
    }
}

/**
 * Circular floating icon control: neutral tinted surface + hairline border +
 * gentle shadow so it stays clearly visible over the ambient background in
 * BOTH light and dark themes (a bare icon was nearly invisible on light).
 */
@Composable
private fun FloatingIconButton(
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String,
) {
    val container = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val tint = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier
            .size(EdgeDimens.minTouch)
            .shadow(2.dp, CircleShape),
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = tint,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ── Section header ─────────────────────────────────────────────────────────

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Uppercase technical label used for provenance / status lines. */
@Composable
fun TechLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        letterSpacing = 0.8.sp,
        modifier = modifier,
    )
}

// ── Helpers ────────────────────────────────────────────────────────────────

@Composable
fun EdgeSpacer(height: Dp = EdgeDimens.spacingM) {
    Spacer(Modifier.height(height))
}

@Composable
fun EdgeSpacerWidth(width: Dp = EdgeDimens.spacingS) {
    Spacer(Modifier.width(width))
}

/** Accent helpers for policy/provenance coloring. */
@Composable
fun edgeBlue(): Color = LocalEdgeColors.current.accentBlue

@Composable
fun edgeViolet(): Color = LocalEdgeColors.current.accentViolet

@Composable
fun edgeAmber(): Color = LocalEdgeColors.current.accentAmber

@Composable
fun edgePositive(): Color = LocalEdgeColors.current.positive

/** Time-based greeting word ("Good morning", ...). Independent of the profile name. */
fun greetingWord(hourOfDay: Int): String = when (hourOfDay) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..20 -> "Good evening"
    else -> "Good night"
}

/** Full greeting line: "Good morning" or "Good morning, Subarno". */
fun greetingFor(hourOfDay: Int, name: String): String {
    val clean = name.trim()
    return if (clean.isEmpty()) greetingWord(hourOfDay) else "${greetingWord(hourOfDay)}, $clean"
}
