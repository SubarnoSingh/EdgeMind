package com.example.EdgeMemo.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.EdgeMemo.ui.theme.DarkAuroraFields
import com.example.EdgeMemo.ui.theme.DarkBackground
import com.example.EdgeMemo.ui.theme.LightAuroraFields
import com.example.EdgeMemo.ui.theme.LightBackground

/**
 * The EdgeMind ambient background: a calm base color under several very
 * large, low-alpha radial fields. Feathering comes from the radial gradient
 * falloff itself — no blur filters, no animation, no recomposition cost.
 * Drawn in a single pass so scrolling stays cheap.
 */
@Composable
fun AuroraBackground(
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val base = if (darkTheme) DarkBackground else LightBackground
    val fields = if (darkTheme) DarkAuroraFields else LightAuroraFields

    Canvas(
        modifier = modifier.fillMaxSize(),
    ) {
        drawRect(base)
        fields.forEach { field ->
            val center = Offset(size.width * field.centerX, size.height * field.centerY)
            val radius = size.width * field.radiusFraction
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(field.color, Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
    }
}
