package com.example.EdgeMemo.core.sync

import com.example.EdgeMemo.core.record.RecordId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SyncOperationIdTest {
    private val recordId = RecordId.fromString("123e4567-e89b-12d3-a456-426614174000")

    @Test
    fun identityIsDeterministic() {
        val first = SyncOperationId.generate(SyncOperationType.UPSERT, recordId, 4)
        val second = SyncOperationId.generate(SyncOperationType.UPSERT, recordId, 4)

        assertEquals("UPSERT:123e4567-e89b-12d3-a456-426614174000:4", first.value)
        assertEquals(first, second)
    }

    @Test
    fun differentVersionsHaveDifferentIdentities() {
        assertNotEquals(
            SyncOperationId.generate(SyncOperationType.UPSERT, recordId, 1),
            SyncOperationId.generate(SyncOperationType.UPSERT, recordId, 2),
        )
    }

    @Test
    fun identityParsesAndRoundTrips() {
        val original = SyncOperationId.generate(SyncOperationType.TOMBSTONE, recordId, 7)
        val parsed = SyncOperationId.parse(original.value)

        assertEquals(original, parsed)
        assertEquals(SyncOperationType.TOMBSTONE, parsed.operationType)
        assertEquals(recordId, parsed.recordId)
        assertEquals(7, parsed.version)
    }

    @Test
    fun malformedUuidIsRejected() {
        assertIllegalArgument { SyncOperationId.parse("UPSERT:not-a-uuid:1") }
    }

    @Test
    fun versionLessThanOneIsRejected() {
        assertIllegalArgument { SyncOperationId.parse("UPSERT:${recordId.uuid}:0") }
        assertIllegalArgument { SyncOperationId.generate(SyncOperationType.UPSERT, recordId, 0) }
    }

    @Test
    fun unknownOperationTypeIsRejected() {
        assertIllegalArgument { SyncOperationId.parse("DELETE:${recordId.uuid}:1") }
    }

    @Test
    fun wrongSegmentCountIsRejected() {
        assertIllegalArgument { SyncOperationId.parse("UPSERT:${recordId.uuid}") }
        assertIllegalArgument { SyncOperationId.parse("UPSERT:${recordId.uuid}:1:extra") }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
