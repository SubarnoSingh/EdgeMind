package com.example.EdgeMemo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
     * Numeric/technical telemetry: tabular monospace so counts, identifiers
     * (`P-101`, `SKF-6205`) and timestamps align in columns.
     */
    val numeric: TextStyle
        @Composable get() = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.5.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.3.sp,
        )

    val metricValue: TextStyle
        @Composable get() = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 26.sp,
            lineHeight = 30.sp,
            letterSpacing = (-0.3).sp,
        )
}

/**
 * Layout tokens beyond the spacing scale in EdgeDimens: the metrics that
 * keep the shell consistent (nav height, screen gutters, card gaps,
 * indicator geometry).
 */
object EdgeLayout {
    val screenPadding: Dp = 20.dp
    val cardPadding: Dp = 16.dp
    val cardGap: Dp = 12.dp
    val sectionGap: Dp = 20.dp
    val listItemGap: Dp = 8.dp
    val compactGap: Dp = 4.dp
    val bottomNavHeight: Dp = 64.dp
    val statusDotSize: Dp = 8.dp
    val indicatorSize: Dp = 18.dp
    val minTarget: Dp = 48.dp
    val hairline: Dp = 0.5.dp
}
