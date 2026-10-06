package com.example.EdgeMemo.ui.theme

import androidx.compose.ui.graphics.Color

// EdgeMind palette — "control room on night shift".
// Borrowed from high-performance HMI practice (ISA-101): greys carry the
// structure, and saturated color only ever means state. Iris is reserved for
// things you can act on; amber/red/green/violet are status, never decoration.

// ── Dark (primary identity) ────────────────────────────────────────────────

val DarkBackground = Color(0xFF15171D) // ink: graphite with a cold cast
val DarkSurface = Color(0xFF1C1F27) // panel
val DarkSurfaceVariant = Color(0xFF232731) // inset / secondary panel
val DarkSurfaceHigh = Color(0xFF2B303B) // raised: inputs, selected rows
val DarkOutline = Color(0xFF3A404D) // control borders
val DarkOutlineVariant = Color(0xFF2C313C) // panel hairlines
val DarkOnSurface = Color(0xFFECEEF2)
val DarkOnSurfaceVariant = Color(0xFFA0A6B4)

val DarkPrimary = Color(0xFF9EA7FF) // iris
val DarkOnPrimary = Color(0xFF151838)
val DarkPrimaryContainer = Color(0xFF2B3062)
val DarkOnPrimaryContainer = Color(0xFFDEE1FF)

val DarkSecondary = Color(0xFFB9BFCC)
val DarkOnSecondary = Color(0xFF1C1F27)
val DarkSecondaryContainer = Color(0xFF2E3340)
val DarkOnSecondaryContainer = Color(0xFFE3E6EE)

val DarkTertiary = Color(0xFFF0B44C) // amber
val DarkOnTertiary = Color(0xFF2E1F00)
val DarkTertiaryContainer = Color(0xFF3B2D12)
val DarkOnTertiaryContainer = Color(0xFFFBDDA6)

val DarkError = Color(0xFFFF7B72)
val DarkOnError = Color(0xFF3A0B07)
val DarkErrorContainer = Color(0xFF45201D)
val DarkOnErrorContainer = Color(0xFFFFD4CF)

val DarkPositive = Color(0xFF5CCB9A)
val DarkPositiveContainer = Color(0xFF173428)

val DarkCodeSurface = Color(0xFF111318)
val DarkCodeText = Color(0xFFD5D9E3)

// ── Light ──────────────────────────────────────────────────────────────────

val LightBackground = Color(0xFFF3F4F7)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF0F1F5)
val LightSurfaceHigh = Color(0xFFE6E8EE)
val LightOutline = Color(0xFFC9CDD7)
val LightOutlineVariant = Color(0xFFE0E3EA)
val LightOnSurface = Color(0xFF171A21)
val LightOnSurfaceVariant = Color(0xFF5B6273)

val LightPrimary = Color(0xFF4A53D6)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE3E5FF)
val LightOnPrimaryContainer = Color(0xFF1E2370)

val LightSecondary = Color(0xFF4B5263)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFE6E8EE)
val LightOnSecondaryContainer = Color(0xFF232733)

val LightTertiary = Color(0xFF9A6200)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFCEFD6)
val LightOnTertiaryContainer = Color(0xFF4A2F00)

val LightError = Color(0xFFC2382E)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFCE3E0)
val LightOnErrorContainer = Color(0xFF5C120C)

val LightPositive = Color(0xFF1F8A5B)
val LightPositiveContainer = Color(0xFFDDF2E7)

val LightCodeSurface = Color(0xFFF0F1F5)
val LightCodeText = Color(0xFF2A2F3B)

// ── Status accents (policy / provenance) ──────────────────────────────────
// blue = cloud / sync, violet = redacted, amber = local-only / queued.

val AccentBlue = Color(0xFF3D73D9)
val AccentBlueDark = Color(0xFF7FB0FF)
val AccentViolet = Color(0xFF7347D0)
val AccentVioletDark = Color(0xFFC3A6FF)
val AccentAmber = Color(0xFF9A6200)
val AccentAmberDark = Color(0xFFF0B44C)

// ── Ambient glow ───────────────────────────────────────────────────────────
// One restrained iris wash behind the header, nothing else.

data class AuroraField(
    val color: Color,
    val centerX: Float,
    val centerY: Float,
    val radiusFraction: Float,
)

val LightAuroraFields = listOf(
    AuroraField(Color(0xFFDADDFB).copy(alpha = 0.55f), 0.0f, -0.04f, 1.1f),
)

val DarkAuroraFields = listOf(
    AuroraField(Color(0xFF2E3466).copy(alpha = 0.45f), 0.0f, -0.04f, 1.1f),
    AuroraField(Color(0xFF2A2340).copy(alpha = 0.30f), 1.0f, 0.02f, 0.8f),
)
