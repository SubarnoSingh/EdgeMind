package com.example.EdgeMemo.presentation.machines

import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.sync.SyncSummary
import com.example.EdgeMemo.ui.theme.EdgeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression tests for the machine-card sync-status mapping. The bug: pending
 * (queued) records were shown as the active "Syncing" state. These assert the
 * card only ever claims syncing when the DURABLE reader reports real IN_FLIGHT
 * operations, and never otherwise.
 */
class MachinesAssetStatusTest {

    private fun asset(
        recordCount: Int = 3,
        pending: Int = 0,
        conflicts: Long = 0L,
    ) = AssetModel.Asset(
        namespace = "p-101",
        recordCount = recordCount,
        lastActivityAt = 1L,
        representativeTitle = "P-101",
        maintenanceCount = recordCount,
        pendingSyncCount = pending,
        unresolvedConflictCount = conflicts,
        types = listOf(MemoryType.REPAIR),
    )

    @Test
    fun pendingRecordsAreQueuedNotSyncingWhenNothingIsInFlight() {
        val s = MachinesAssetStatus.of(
            asset(pending = 2),
            SyncSummary(pending = 2, syncing = 0),
            isOnline = true,
        )
        assertNotEquals(EdgeStatus.SYNCING, s.status)
        assertEquals(EdgeStatus.WARNING, s.status)
        assertEquals("queued", s.label)
    }

    @Test
    fun syncingOnlyWhenDurableInFlightIsPositive() {
        val s = MachinesAssetStatus.of(
            asset(pending = 2),
            SyncSummary(pending = 2, syncing = 1),
            isOnline = true,
        )
        assertEquals(EdgeStatus.SYNCING, s.status)
        assertEquals("syncing", s.label)
    }

    @Test
    fun pendingWhileOfflineIsQueuedOfflineNotSyncing() {
        val s = MachinesAssetStatus.of(
            asset(pending = 2),
            SyncSummary(pending = 2, syncing = 0),
            isOnline = false,
        )
        assertEquals(EdgeStatus.OFFLINE, s.status)
        assertEquals("queued offline", s.label)
    }

    @Test
    fun conflictsTakePriorityOverQueuedSync() {
        val s = MachinesAssetStatus.of(
            asset(pending = 2, conflicts = 1L),
            SyncSummary(pending = 2, syncing = 5),
            isOnline = true,
        )
        assertEquals(EdgeStatus.WARNING, s.status)
        assertEquals("conflict", s.label)
    }

    @Test
    fun fullySyncedAssetIsReadyEvenIfOtherAssetsAreInFlight() {
        // pendingSyncCount == 0 for this asset: never shows "syncing" just
        // because some other machine happens to have an IN_FLIGHT operation.
        val s = MachinesAssetStatus.of(
            asset(pending = 0),
            SyncSummary(syncing = 3),
            isOnline = true,
        )
        assertNotEquals(EdgeStatus.SYNCING, s.status)
        assertEquals(EdgeStatus.HEALTHY, s.status)
        assertEquals("ready", s.label)
    }

    @Test
    fun emptyAssetIsNeutral() {
        val s = MachinesAssetStatus.of(
            asset(recordCount = 0, pending = 0),
            SyncSummary(),
            isOnline = true,
        )
        assertEquals(EdgeStatus.NEUTRAL, s.status)
        assertEquals("no records", s.label)
    }

    @Test
    fun neverReportsSyncingWithoutInFlightAcrossTheMatrix() {
        val noFlight = SyncSummary(pending = 9, failed = 4, synced = 2, syncing = 0)
        for (pending in 0..3) {
            for (online in listOf(true, false)) {
                val s = MachinesAssetStatus.of(asset(pending = pending), noFlight, online)
                assertNotEquals(
                    "asset(pending=$pending, online=$online) must not claim syncing with 0 in-flight",
                    EdgeStatus.SYNCING,
                    s.status,
                )
            }
        }
    }
}
