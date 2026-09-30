package com.example.EdgeMemo.core.sync

import com.example.EdgeMemo.core.record.RecordId
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncClassificationTest {
    private val id = RecordId.fromString("123e4567-e89b-12d3-a456-426614174000")

    @Test
    fun phase12VersionMatrixAndTombstoneCases() {
        val cases = listOf(
            Case("absent", null, snapshot(version = 1, hash = "a"), SyncClassification.NEW),
            Case("same version same hash", snapshot(7, "a"), snapshot(7, "a"), SyncClassification.DUPLICATE),
            Case("same version different hash", snapshot(7, "a"), snapshot(7, "b"), SyncClassification.CONFLICT),
            Case("lower version", snapshot(7, "a"), snapshot(6, "b"), SyncClassification.STALE),
            Case("higher version", snapshot(7, "a"), snapshot(8, "b"), SyncClassification.UPDATE),
            Case("tombstone cannot be resurrected by stale version", snapshot(8, "dead", true), snapshot(7, "live"), SyncClassification.STALE),
            Case("tombstone blocks same-version active resurrection", snapshot(8, "dead", true), snapshot(8, "dead"), SyncClassification.STALE),
            Case("active record rejects same-version tombstone", snapshot(8, "live"), snapshot(8, "live", true), SyncClassification.CONFLICT),
            Case("active record accepts newer tombstone as update", snapshot(7, "live"), snapshot(8, "dead", true), SyncClassification.UPDATE),
            Case("same tombstone delivery is duplicate", snapshot(8, "dead", true), snapshot(8, "dead", true), SyncClassification.DUPLICATE),
            Case("explicit newer restore is versioned update", snapshot(8, "dead", true), snapshot(9, "live"), SyncClassification.UPDATE),
        )

        cases.forEach { testCase ->
            assertEquals(testCase.name, testCase.expected, SyncClassification.classify(testCase.current, testCase.incoming))
        }
    }

    @Test
    fun differentLogicalRecordIdsAreRejected() {
        val otherId = RecordId.fromString("123e4567-e89b-12d3-a456-426614174001")

        try {
            SyncClassification.classify(
                current = snapshot(3, "live"),
                incoming = SyncRecordSnapshot(otherId, 4, "new"),
            )
            throw AssertionError("Expected mismatched record ids to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun classificationDoesNotTreatTombstoneAsAnOperationType() {
        val result = SyncClassification.classify(
            current = snapshot(3, "live"),
            incoming = snapshot(4, "dead", tombstone = true),
        )

        assertEquals(SyncClassification.UPDATE, result)
    }

    private fun snapshot(
        version: Int,
        hash: String,
        tombstone: Boolean = false,
    ) = SyncRecordSnapshot(id, version, hash, tombstone)

    private data class Case(
        val name: String,
        val current: SyncRecordSnapshot?,
        val incoming: SyncRecordSnapshot,
        val expected: SyncClassification,
    )
}
