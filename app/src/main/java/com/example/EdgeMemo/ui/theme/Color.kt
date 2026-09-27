package com.example.EdgeMemo.ui.theme

import androidx.compose.ui.graphics.Color

// ── Light theme ────────────────────────────────────────────────────────────
// Soft cool-white base with lavender-blue-gray surfaces (reference language)
// and a calm indigo-blue accent. Deliberately low saturation.

val LightBackground = Color(0xFFF1F4FA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF2F4FA)
val LightSurfaceHigh = Color(0xFFE6EAF4)
val LightOutline = Color(0xFFD8DEEC)
val LightOnSurface = Color(0xFF1B2233)
val LightOnSurfaceVariant = Color(0xFF5A6478)

val LightPrimary = Color(0xFF4F70E2)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFDEE5FB)
val LightOnPrimaryContainer = Color(0xFF24356E)

val LightSecondary = Color(0xFF7A5FD0)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFE9E3FA)
val LightOnSecondaryContainer = Color(0xFF3C2E6B)

val LightTertiary = Color(0xFFA57834)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFF6EAD4)
val LightOnTertiaryContainer = Color(0xFF4E3A16)

val LightError = Color(0xFFB8433A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFF9DFDD)

val LightPositive = Color(0xFF2E7D5B)
val LightPositiveContainer = Color(0xFFDDF1E7)

val LightCodeSurface = Color(0xFFF4F5FA)
val LightCodeText = Color(0xFF323A4E)

// ── Dark theme ─────────────────────────────────────────────────────────────
// A deliberate dark treatment (not an inversion): near-black charcoal with
// muted purple/blue tints, matching EdgeMind's engineering-tool identity.

val DarkBackground = Color(0xFF0D1017)
val DarkSurface = Color(0xFF151A24)
val DarkSurfaceVariant = Color(0xFF1D2433)
val DarkSurfaceHigh = Color(0xFF262E40)
val DarkOutline = Color(0xFF2B3448)
val DarkOnSurface = Color(0xFFE7EBF5)
val DarkOnSurfaceVariant = Color(0xFF9AA5BC)

val DarkPrimary = Color(0xFFA8B6F4)
val DarkOnPrimary = Color(0xFF1B2753)
val DarkPrimaryContainer = Color(0xFF2C3A6E)
val DarkOnPrimaryContainer = Color(0xFFDDE4FC)

val DarkSecondary = Color(0xFFC0B0EC)
val DarkOnSecondary = Color(0xFF2E215C)
val DarkSecondaryContainer = Color(0xFF4A3B80)
val DarkOnSecondaryContainer = Color(0xFFEAE4FB)

val DarkTertiary = Color(0xFFE2B77E)
val DarkOnTertiary = Color(0xFF3F2E10)
val DarkTertiaryContainer = Color(0xFF57421C)
val DarkOnTertiaryContainer = Color(0xFFF8E9CF)

val DarkError = Color(0xFFF08C82)
val DarkOnError = Color(0xFF3D1410)
val DarkErrorContainer = Color(0xFF55241F)

val DarkPositive = Color(0xFF7CC9A5)
val DarkPositiveContainer = Color(0xFF1E3A2E)

val DarkCodeSurface = Color(0xFF1A2030)
val DarkCodeText = Color(0xFFD6DCEA)

// ── Shared semantic accents (used by provenance / policy chips) ───────────
// EdgeMind: blue = sync/cloud, violet = redacted, amber = local-only.

val AccentBlue = Color(0xFF4F70E2)
val AccentBlueDark = Color(0xFFA8B6F4)
val AccentViolet = Color(0xFF7A5FD0)
val AccentVioletDark = Color(0xFFC0B0EC)
val AccentAmber = Color(0xFFA57834)
val AccentAmberDark = Color(0xFFE2B77E)

// ── Aurora ambient background fields ──────────────────────────────────────
// Soft daylight-through-frosted-glass tones (light) and a deep, atmospheric
// night composition (dark). Each field is a large, heavily feathered radial
// field drawn at low alpha over the theme base color. Almost flat at first
// glance; the atmosphere becomes visible on closer look.

data class AuroraField(
    val color: Color,
    val centerX: Float,
    val centerY: Float,
    val radiusFraction: Float,
)

val LightAuroraFields = listOf(
    AuroraField(Color(0xFFBBD2F2).copy(alpha = 0.42f), 0.10f, 0.02f, 1.15f), // soft blue, top-left
    AuroraField(Color(0xFFD8D0F5).copy(alpha = 0.36f), 0.94f, 0.16f, 1.00f), // lavender / periwinkle
    AuroraField(Color(0xFFF1DAE6).copy(alpha = 0.30f), 0.80f, 0.55f, 0.90f), // subtle pink / lilac
    AuroraField(Color(0xFFC9E2EF).copy(alpha = 0.28f), 0.22f, 0.60f, 0.95f), // pale cool cyan
    AuroraField(Color(0xFFE7EAF8).copy(alpha = 0.55f), 0.45f, 1.02f, 1.05f), // cool-white lift, bottom
)

val DarkAuroraFields = listOf(
    AuroraField(Color(0xFF2B3866).copy(alpha = 0.50f), 0.12f, 0.04f, 1.15f), // deep indigo
    AuroraField(Color(0xFF322A58).copy(alpha = 0.38f), 0.92f, 0.24f, 1.00f), // muted violet
    AuroraField(Color(0xFF1B3053).copy(alpha = 0.38f), 0.22f, 0.88f, 1.05f), // soft deep blue
    AuroraField(Color(0xFF3A2244).copy(alpha = 0.26f), 0.74f, 0.68f, 0.85f), // restrained magenta / lilac
    AuroraField(Color(0xFF161B2B).copy(alpha = 0.45f), 0.50f, 1.02f, 1.00f), // bottom depth wash
)
