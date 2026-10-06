package com.example.EdgeMemo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightBackground,
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = LightSurfaceVariant,
    surfaceContainerHighest = LightSurfaceHigh,
    surfaceBright = LightSurfaceHigh,
    surfaceDim = LightBackground,
    surfaceTint = LightSurface,
    inverseSurface = LightOnSurface,
    inverseOnSurface = LightSurface,
    scrim = Color(0x99000000),
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceVariant,
    surfaceContainerHighest = DarkSurfaceHigh,
    surfaceBright = DarkSurfaceHigh,
    surfaceDim = DarkBackground,
    surfaceTint = DarkSurface,
    inverseSurface = DarkOnSurface,
    inverseOnSurface = DarkSurface,
    scrim = Color(0x99000000),
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
)

// Design tokens for elements Material3 colorScheme does not carry.
data class EdgeColors(
    val positive: Color,
    val positiveContainer: Color,
    val codeSurface: Color,
    val codeText: Color,
    val accentBlue: Color,
    val accentViolet: Color,
    val accentAmber: Color,
)

val LocalEdgeColors = androidx.compose.runtime.staticCompositionLocalOf {
    EdgeColors(
        positive = LightPositive,
        positiveContainer = LightPositiveContainer,
        codeSurface = LightCodeSurface,
        codeText = LightCodeText,
        accentBlue = AccentBlue,
        accentViolet = AccentViolet,
        accentAmber = AccentAmber,
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val edgeColors = if (darkTheme) {
        EdgeColors(
            positive = DarkPositive,
            positiveContainer = DarkPositiveContainer,
            codeSurface = DarkCodeSurface,
            codeText = DarkCodeText,
            accentBlue = AccentBlueDark,
            accentViolet = AccentVioletDark,
            accentAmber = AccentAmberDark,
        )
    } else {
        EdgeColors(
            positive = LightPositive,
            positiveContainer = LightPositiveContainer,
            codeSurface = LightCodeSurface,
            codeText = LightCodeText,
            accentBlue = AccentBlue,
            accentViolet = AccentViolet,
            accentAmber = AccentAmber,
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalEdgeColors provides edgeColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = EdgeShapes,
            content = content,
        )
    }
}
