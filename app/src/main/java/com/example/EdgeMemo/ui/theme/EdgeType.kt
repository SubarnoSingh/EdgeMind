package com.example.EdgeMemo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Semantic typography roles for the industrial UI. Screens compose against
 * these roles, never raw Material slots, so hierarchy stays centralized:
 * screen title / section title / body / metadata / numeric telemetry /
 * uppercase technical label.
 */
object EdgeType {
    val screenTitle: TextStyle
        @Composable get() = MaterialTheme.typography.headlineSmall
    val sectionTitle: TextStyle
        @Composable get() = MaterialTheme.typography.titleMedium
    val body: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium
    val bodyEmphasis: TextStyle
        @Composable get() = MaterialTheme.typography.bodyLarge
    val metadata: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall
    val label: TextStyle
        @Composable get() = MaterialTheme.typography.labelMedium
    val buttonLabel: TextStyle
        @Composable get() = MaterialTheme.typography.labelLarge

    /**
     * Equipment tags (`P-101`, `SKF-6205`), counts and timestamps: the
     * Expanded width, so tags read like a stamped nameplate. (Mona Sans'
     * tabular figures swap in a slashed zero that reads as "Ø", so they stay off.)
     */
    val numeric: TextStyle
        @Composable get() = TextStyle(
            fontFamily = MonaSansExpanded,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.2.sp,
        )

    val metricValue: TextStyle
        @Composable get() = TextStyle(
            fontFamily = MonaSansExpanded,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            lineHeight = 32.sp,
            letterSpacing = (-0.8).sp,
        )

    /** The machine's own tag, set large on its detail screen. */
    val nameplate: TextStyle
        @Composable get() = TextStyle(
            fontFamily = MonaSansExpanded,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            lineHeight = 38.sp,
            letterSpacing = (-1).sp,
        )

    /** Code inside answers and raw identifiers in diagnostics. */
    val code: TextStyle
        @Composable get() = TextStyle(
            fontFamily = MonaSansMono,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
}

/**
 * Layout tokens beyond the spacing scale in EdgeDimens: the metrics that
 * keep the shell consistent (nav height, screen gutters, card gaps,
 * indicator geometry).
 */
object EdgeLayout {
    val screenPadding: Dp = 18.dp
    val cardPadding: Dp = 16.dp
    val cardGap: Dp = 10.dp
    val sectionGap: Dp = 28.dp
    val listItemGap: Dp = 8.dp
    val compactGap: Dp = 4.dp
    val bottomNavHeight: Dp = 62.dp
    val statusDotSize: Dp = 7.dp
    val indicatorSize: Dp = 18.dp
    val minTarget: Dp = 48.dp
    val hairline: Dp = 1.dp
}
