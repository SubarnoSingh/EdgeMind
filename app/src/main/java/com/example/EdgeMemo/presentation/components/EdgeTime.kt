package com.example.EdgeMemo.presentation.components

/**
 * Industrial time display helper: compact relative labels for telemetry
 * lists. Pure function of (epochMillis, now) — deterministic and testable;
 * no wall-clock reads inside composables.
 */
fun relativeTimeLabel(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0L) return "—"
    val delta = nowMillis - epochMillis
    return when {
        delta < 0 -> "just now"
        delta < 60_000L -> "just now"
        delta < 3_600_000L -> "${delta / 60_000L}m ago"
        delta < 86_400_000L -> "${delta / 3_600_000L}h ago"
        delta < 7 * 86_400_000L -> "${delta / 86_400_000L}d ago"
        else -> "${delta / (30 * 86_400_000L)}mo ago"
    }
}
