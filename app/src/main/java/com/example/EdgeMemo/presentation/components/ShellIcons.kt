package com.example.EdgeMemo.presentation.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Shell navigation icons — drawn locally with the same stroked geometry as
 * the Sun/Moon/Settings set in EdgeComponents (material-icons-core has no
 * industrial equivalents). Consistent 24vp viewport, 1.7dp round strokes.
 */

val DashboardIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Dashboard",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 2x2 instrument grid, top-left cell taller (overview panel).
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            strokedRect(3.5f, 3.5f, 7f, 9f)
            strokedRect(13.5f, 3.5f, 7f, 5.5f)
            strokedRect(3.5f, 15.5f, 7f, 5f)
            strokedRect(13.5f, 12f, 7f, 8.5f)
        }
    }.build()
}

val MachinesIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Machines",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Isometric asset block with a bolt dot.
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 2.8f)
            lineTo(20f, 7.2f)
            lineTo(20f, 16.4f)
            lineTo(12f, 20.8f)
            lineTo(4f, 16.4f)
            lineTo(4f, 7.2f)
            close()
            moveTo(4f, 7.2f)
            lineTo(12f, 11.6f)
            lineTo(20f, 7.2f)
            moveTo(12f, 11.6f)
            lineTo(12f, 20.8f)
        }
    }.build()
}

val AskIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Ask",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Terminal prompt "> _" — query, not chat-bubble aesthetic.
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4.5f, 6.5f)
            lineTo(9f, 11.5f)
            lineTo(4.5f, 16.5f)
            moveTo(12f, 18.5f)
            lineTo(19.5f, 18.5f)
        }
    }.build()
}

val SyncIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Sync",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Circular sync arrows.
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4.8f, 9.2f)
            arcTo(7.6f, 7.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 17.6f, 6.9f)
            moveTo(19.2f, 14.8f)
            arcTo(7.6f, 7.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 6.4f, 17.1f)
            moveTo(17.9f, 3.4f)
            lineTo(18f, 7.2f)
            lineTo(14.2f, 7.3f)
            moveTo(6.1f, 20.6f)
            lineTo(6f, 16.8f)
            lineTo(9.8f, 16.7f)
        }
    }.build()
}

// Three sliders: settings as adjustable controls, same stroke as the set.
val SettingsIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Settings",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4f, 6.5f); lineTo(20f, 6.5f)
            moveTo(4f, 12f); lineTo(20f, 12f)
            moveTo(4f, 17.5f); lineTo(20f, 17.5f)
        }
        path(fill = SolidColor(Color.Black), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            circle(9f, 6.5f, 2.1f)
            circle(15.5f, 12f, 2.1f)
            circle(7.5f, 17.5f, 2.1f)
        }
    }.build()
}

val BackIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Back",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(14.5f, 5.5f)
            lineTo(8f, 12f)
            lineTo(14.5f, 18.5f)
        }
    }.build()
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, cx + r, cy)
    arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, cx - r, cy)
    close()
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.strokedRect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) {
    moveTo(x, y)
    lineTo(x + w, y)
    lineTo(x + w, y + h)
    lineTo(x, y + h)
    close()
}
