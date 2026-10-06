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
    val cardRadius = 14.dp
    val pillRadius = 12.dp
    val inputRadius = 14.dp
    val chipRadius = 6.dp
    val minTouch = 48.dp
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

// ── Cards ──────────────────────────────────────────────────────────────────

/**
 * The standard EdgeMind panel: flat, hairline-bordered, no shadow. Hierarchy
 * comes from the surface ladder and the border, never from elevation.
 */
@Composable
fun EdgeCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(EdgeDimens.cardRadius),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentPadding: PaddingValues = PaddingValues(EdgeDimens.spacingL),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * Inset panel one step below [EdgeCard]: summaries, metadata blocks, rows
 * inside a list. Tinted fill, no border, tighter corners.
 */
@Composable
fun EdgeCardSecondary(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * A run of rows inside one panel, split by hairlines — used instead of a
 * stack of separate cards so lists read as one instrument, not a card pile.
 */
@Composable
fun <T> EdgeListGroup(
    items: List<T>,
    modifier: Modifier = Modifier,
    row: @Composable (T) -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(EdgeDimens.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = EdgeDimens.spacingL)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
                row(item)
            }
        }
    }
}

// ── Chips / buttons ────────────────────────────────────────────────────────

/** Small status chip with an optional leading dot. Square-ish, not a pill. */
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
            .background(containerColor, RoundedCornerShape(EdgeDimens.chipRadius))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showDot) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(color, CircleShape),
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

/** Primary action: filled iris, 48dp tall, softly squared corners. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    border: BorderStroke? = null,
) {
    Surface(
        modifier = modifier.defaultMinSize(minHeight = EdgeDimens.minTouch),
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(EdgeDimens.pillRadius),
        color = if (enabled) containerColor else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EdgeDimens.spacingS, Alignment.CenterHorizontally),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Secondary action: panel-colored with a control border. */
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
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (enabled) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
    )
}

// ── Section header ─────────────────────────────────────────────────────────

/**
 * Section heading: a plain title with optional one-line context and an
 * optional trailing action (e.g. "See all"). No eyebrow, no caps.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Small supporting label (provenance, record type, section context). Sentence
 * case as written by the caller; never forced to capitals.
 */
@Composable
fun TechLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
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
