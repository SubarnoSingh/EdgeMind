package com.example.EdgeMemo.core.sync

import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.SyncDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SyncOperationRecordTest {
    private val recordId = RecordId.fromString("123e4567-e89b-12d3-a456-426614174000")

    @Test
    fun constructionValidatesRequiredDomainInvariants() {
        val operation = validOperation()

        assertEquals(recordId, operation.recordId)
        assertEquals(SyncOperationType.UPSERT, operation.operationType)
        assertEquals(1, operation.version)
    }

    @Test
    fun operationIdMustMatchOperationTypeRecordAndVersion() {
        assertIllegalArgument {
            validOperation(
                operationId = SyncOperationId.generate(SyncOperationType.UPSERT, recordId, 2),
            )
        }
    }

    @Test
    fun envelopeMapRoundTripsDomainObject() {
        val operation = validOperation(
            operationType = SyncOperationType.TOMBSTONE,
            version = 3,
            attempts = 2,
            lastError = SyncFailureKind.NETWORK,
            leaseUntil = 2_000L,
            syncDecision = SyncDecision.SYNC_REDACTED,
            redacted = true,
            payload = mapOf(
                "title" to JsonValue.fromString("Pump"),
                "content" to JsonValue.fromString("Seal failure"),
                "nested" to JsonValue.fromMap(mapOf("severity" to JsonValue.fromString("high"))),
            ),
        )

        val restored = SyncOperationRecord.fromEnvelopeMap(operation.toEnvelopeMap())

        assertEquals(operation, restored)
    }

    @Test
    fun equalityUsesAllDomainFields() {
        val first = validOperation()
        val same = validOperation()
        val changed = validOperation(payload = mapOf("content" to JsonValue.fromString("different")))

        assertEquals(first, same)
        assertNotEquals(first, changed)
    }

    @Test
    fun localOnlyOperationsAreRejected() {
        assertIllegalArgument {
            validOperation(syncDecision = SyncDecision.LOCAL_ONLY)
        }
    }

    @Test
    fun invalidEnvelopeMapsAreRejected() {
        val map = validOperation().toEnvelopeMap().toMutableMap()
        map["operation_id"] = JsonValue.fromString("UPSERT:not-a-uuid:1")

        assertIllegalArgument { SyncOperationRecord.fromEnvelopeMap(map) }
    }

    @Test
    fun localOnlyIsRejectedWhenDecodedFromEnvelope() {
        val map = validOperation().toEnvelopeMap().toMutableMap()
        map["_sync_decision"] = JsonValue.fromString(SyncDecision.LOCAL_ONLY.name)

        assertIllegalArgument { SyncOperationRecord.fromEnvelopeMap(map) }
    }

    @Test
    fun redactedDecisionRequiresRedactedRepresentation() {
        assertIllegalArgument {
            validOperation(syncDecision = SyncDecision.SYNC_REDACTED, redacted = false)
        }
    }

    @Test
    fun reservedRecordEnvelopeFieldsCannotEnterDomainPayload() {
        assertIllegalArgument {
            validOperation(payload = mapOf("_tombstone" to JsonValue.fromBoolean(true)))
        }
    }

    @Test
    fun malformedLeaseIsRejected() {
        val map = validOperation().toEnvelopeMap().toMutableMap()
        map["_lease_until"] = JsonValue.fromDouble(1.5)

        assertIllegalArgument { SyncOperationRecord.fromEnvelopeMap(map) }
    }

    private fun validOperation(
        operationId: SyncOperationId? = null,
        operationType: SyncOperationType = SyncOperationType.UPSERT,
        version: Int = 1,
        attempts: Int = 0,
        lastError: SyncFailureKind? = null,
        leaseUntil: Long? = null,
        syncDecision: SyncDecision = SyncDecision.SYNC,
        redacted: Boolean = false,
        payload: Map<String, JsonValue> = mapOf("content" to JsonValue.fromString("Pump")),
    ) = SyncOperationRecord(
        recordId = recordId,
        operationId = operationId ?: SyncOperationId.generate(operationType, recordId, version),
        operationType = operationType,
        state = OutboxOperationState.PENDING,
        attempts = attempts,
        lastError = lastError,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        leaseUntil = leaseUntil,
        version = version,
        syncDecision = syncDecision,
        redacted = redacted,
        payload = payload,
    )

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
