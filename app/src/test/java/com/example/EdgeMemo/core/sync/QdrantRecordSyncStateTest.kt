package com.example.EdgeMemo.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QdrantRecordSyncStateTest {
    @Test
    fun tombstoneRequiresVersionBump() {
        assertFalse(
            QdrantRecordSyncState.isLegalTransition(
                QdrantRecordSyncState.SYNCED,
                QdrantRecordSyncState.TOMBSTONED,
                currentVersion = 3,
                nextVersion = 3,
            ),
        )
        assertTrue(
            QdrantRecordSyncState.isLegalTransition(
                QdrantRecordSyncState.SYNCED,
                QdrantRecordSyncState.TOMBSTONED,
                currentVersion = 3,
                nextVersion = 4,
            ),
        )
    }

    @Test
    fun restoreRequiresVersionBump() {
        assertFalse(
            QdrantRecordSyncState.isLegalTransition(
                QdrantRecordSyncState.TOMBSTONED,
                QdrantRecordSyncState.PENDING,
                currentVersion = 4,
                nextVersion = 4,
            ),
        )
        assertTrue(
            QdrantRecordSyncState.isLegalTransition(
                QdrantRecordSyncState.TOMBSTONED,
                QdrantRecordSyncState.PENDING,
                currentVersion = 4,
                nextVersion = 5,
            ),
        )
    }

    @Test
    fun syncedToPendingWithoutVersionBumpIsIllegal() {
        assertFalse(
            QdrantRecordSyncState.isLegalTransition(
                QdrantRecordSyncState.SYNCED,
                QdrantRecordSyncState.PENDING,
                currentVersion = 2,
                nextVersion = 2,
            ),
        )
        assertTrue(
            QdrantRecordSyncState.isLegalTransition(
                QdrantRecordSyncState.SYNCED,
                QdrantRecordSyncState.PENDING,
                currentVersion = 2,
                nextVersion = 3,
            ),
        )
    }

    @Test
    fun pendingAcknowledgementKeepsVersion() {
        assertTrue(
            QdrantRecordSyncState.isLegalTransition(
                QdrantRecordSyncState.PENDING,
                QdrantRecordSyncState.SYNCED,
                currentVersion = 2,
                nextVersion = 2,
            ),
        )
        assertFalse(
            QdrantRecordSyncState.isLegalTransition(
                QdrantRecordSyncState.PENDING,
                QdrantRecordSyncState.SYNCED,
                currentVersion = 2,
                nextVersion = 3,
            ),
        )
    }

    @Test
    fun nonPendingStateCannotChangeVersionWithoutAWriteTransition() {
        assertFalse(
            QdrantRecordSyncState.isLegalTransition(
                QdrantRecordSyncState.SYNCED,
                QdrantRecordSyncState.SYNCED,
                currentVersion = 2,
                nextVersion = 3,
            ),
        )
        assertFalse(
            QdrantRecordSyncState.isLegalTransition(
                QdrantRecordSyncState.CONFLICT,
                QdrantRecordSyncState.PENDING,
                currentVersion = 2,
                nextVersion = 2,
            ),
        )
        assertTrue(
            QdrantRecordSyncState.isLegalTransition(
                QdrantRecordSyncState.TOMBSTONED,
                QdrantRecordSyncState.SYNCED,
                currentVersion = 2,
                nextVersion = 3,
            ),
        )
    }
}
