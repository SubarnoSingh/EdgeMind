package com.example.EdgeMemo.presentation.machines

import com.example.EdgeMemo.core.sync.SyncSummary
import com.example.EdgeMemo.ui.theme.EdgeStatus

/**
 * Truthful, pure mapping from REAL record-derived data and the DURABLE sync
 * operation store to a machine-card status. Kept out of the composable so it
 * is unit-testable without Compose.
 *
 * The previous bug mapped `pendingSyncCount > 0` directly to
 * [EdgeStatus.SYNCING], so every machine with queued-but-not-yet-sent records
 * pulsed "syncing" forever. That conflates three genuinely different states:
 *
 *  - PENDING operations waiting for a backend/connectivity  → queued/offline,
 *  - IN_FLIGHT operations the durable reader reports right now → syncing,
 *  - ACKED records                                            → not pending at all.
 *
 * Rules (never claims more than the durable store says):
 *  - Syncing is shown ONLY when [SyncSummary.syncing] (real IN_FLIGHT
 *    operations) is non-zero AND this asset still has unsynced work.
 *  - Pending with no in-flight work is "queued" (or "queued offline" when the
 *    device is offline) — attention, not an active transfer.
 *  - Unresolved conflicts keep the highest priority, as before.
 *  - Failed/DEAD operations are surfaced globally by the sync reader/dashboard;
 *    records that were never sanctioned (LOCAL_ONLY) carry a zero pending count
 *    and read as ready — local-only semantics are preserved.
 */
object MachinesAssetStatus {

    data class CardStatus(val status: EdgeStatus, val label: String)

    fun of(asset: AssetModel.Asset, sync: SyncSummary, isOnline: Boolean): CardStatus = when {
        asset.unresolvedConflictCount > 0L -> CardStatus(EdgeStatus.WARNING, "conflict")
        asset.pendingSyncCount > 0 -> when {
            sync.syncing > 0L -> CardStatus(EdgeStatus.SYNCING, "syncing")
            !isOnline -> CardStatus(EdgeStatus.OFFLINE, "queued offline")
            else -> CardStatus(EdgeStatus.WARNING, "queued")
        }
        asset.recordCount > 0 -> CardStatus(EdgeStatus.HEALTHY, "ready")
        else -> CardStatus(EdgeStatus.NEUTRAL, "no records")
    }
}
