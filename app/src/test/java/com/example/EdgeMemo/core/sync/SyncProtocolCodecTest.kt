package com.example.EdgeMemo.core.sync

import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.SyncDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProtocolCodecTest {
    private val recordId = RecordId.fromString("123e4567-e89b-12d3-a456-426614174000")

    @Test
    fun operationEnvelopeSerializesAndDeserializes() {
        val operation = operation()

        val restored = SyncProtocolCodec.decodeOperation(
            SyncProtocolCodec.encodeOperation(operation),
        )

        assertEquals(operation, restored)
    }

    @Test
    fun serializationIsDeterministicAndUsesFrozenEnvelopeNames() {
        val operation = operation(
            payload = mapOf(
                "z" to JsonValue.fromString("last"),
                "a" to JsonValue.fromString("first"),
            ),
        )
        val encoded = SyncProtocolCodec.encodeOperation(operation)

        assertEquals(encoded, SyncProtocolCodec.encodeOperation(operation))
        assertTrue(encoded.contains("\"_record_type\":\"outbox_op\""))
        assertTrue(encoded.contains("\"operation_id\":\"UPSERT:123e4567-e89b-12d3-a456-426614174000:3\""))
        assertTrue(encoded.indexOf("\"a\"") < encoded.indexOf("\"z\""))
    }

    @Test
    fun operationIdMismatchIsRejected() {
        val encoded = SyncProtocolCodec.encodeOperation(operation())
            .replace(
                "UPSERT:123e4567-e89b-12d3-a456-426614174000:3",
                "UPSERT:123e4567-e89b-12d3-a456-426614174000:4",
            )

        assertIllegalArgument { SyncProtocolCodec.decodeOperation(encoded) }
    }

    @Test
    fun malformedIdentityVersionTypeAndUuidAreRejected() {
        val encoded = SyncProtocolCodec.encodeOperation(operation())
        assertIllegalArgument { SyncProtocolCodec.decodeOperation(encoded.replace("UPSERT:", "DELETE:")) }
        assertIllegalArgument { SyncProtocolCodec.decodeOperation(encoded.replace(":3\"", ":0\"")) }
        assertIllegalArgument {
            SyncProtocolCodec.decodeOperation(
                encoded.replace(recordId.uuid, "not-a-uuid"),
            )
        }
    }

    @Test
    fun unknownOperationStateAndInvalidDecisionAreRejected() {
        val encoded = SyncProtocolCodec.encodeOperation(operation())
        assertIllegalArgument { SyncProtocolCodec.decodeOperation(encoded.replace("\"_state\":\"PENDING\"", "\"_state\":\"UNKNOWN\"")) }
        assertIllegalArgument { SyncProtocolCodec.decodeOperation(encoded.replace("\"_sync_decision\":\"SYNC\"", "\"_sync_decision\":\"LOCAL_ONLY\"")) }
    }

    @Test
    fun malformedPayloadAndTrailingJsonAreRejected() {
        assertIllegalArgument { SyncProtocolCodec.decodeOperation("[]") }
        assertIllegalArgument { SyncProtocolCodec.decodeOperation("${SyncProtocolCodec.encodeOperation(operation())} trailing") }
    }

    @Test
    fun tombstoneOperationKeepsOperationTypeAndVersionedPayload() {
        val tombstone = operation(
            operationType = SyncOperationType.TOMBSTONE,
            payload = mapOf(
                "tombstone" to JsonValue.fromBoolean(true),
                "deletedAt" to JsonValue.fromLong(1_700_000_000_000L),
            ),
        )
        val encoded = SyncProtocolCodec.encodeOperation(tombstone)
        val restored = SyncProtocolCodec.decodeOperation(encoded)

        assertTrue(encoded.contains("\"_operation_type\":\"TOMBSTONE\""))
        assertEquals(SyncOperationType.TOMBSTONE, restored.operationType)
        assertEquals(JsonValue.fromBoolean(true), restored.payload["tombstone"])
    }

    @Test
    fun syncAndRedactedDecisionsRoundTrip() {
        assertEquals(SyncDecision.SYNC, SyncProtocolCodec.decodeOperation(SyncProtocolCodec.encodeOperation(operation())).syncDecision)
        assertEquals(
            SyncDecision.SYNC_REDACTED,
            SyncProtocolCodec.decodeOperation(
                SyncProtocolCodec.encodeOperation(operation(syncDecision = SyncDecision.SYNC_REDACTED, redacted = true)),
            ).syncDecision,
        )
    }

    @Test
    fun localOnlyCannotBecomeAnOperation() {
        assertIllegalArgument {
            operation(syncDecision = SyncDecision.LOCAL_ONLY)
        }
    }

    @Test
    fun responseRoundTripsAndCarriesCloudState() {
        val response = SyncProtocolResponse(
            operationId = SyncOperationId.generate(SyncOperationType.UPSERT, recordId, 3),
            recordId = recordId,
            status = SyncResponseStatus.DUPLICATE,
            cloudVersion = 3,
            cloudContentHash = "a".repeat(64),
            cloudTombstone = false,
        )

        assertEquals(response, SyncProtocolCodec.decodeResponse(SyncProtocolCodec.encodeResponse(response)))
    }

    @Test
    fun malformedResponsesAreRejected() {
        val response = SyncProtocolCodec.encodeResponse(
            SyncProtocolResponse(
                operationId = SyncOperationId.generate(SyncOperationType.UPSERT, recordId, 3),
                recordId = recordId,
                status = SyncResponseStatus.APPLIED,
                cloudVersion = 3,
                cloudContentHash = null,
                cloudTombstone = null,
            ),
        )
        assertIllegalArgument { SyncProtocolCodec.decodeResponse(response.replace("\"status\":\"APPLIED\"", "\"status\":\"NOPE\"")) }
        assertIllegalArgument { SyncProtocolCodec.decodeResponse(response.replace("\"memoryId\":", "\"memoryId\":\"bad\" //")) }
    }

    @Test
    fun contentHashIsStableAcrossProtocolRoundTrip() {
        val operation = operation(
            payload = mapOf(
                "content" to JsonValue.fromString("Pump procedure"),
                "title" to JsonValue.fromString("Procedure"),
                "metadata" to JsonValue.fromMap(mapOf("scope" to JsonValue.fromString("site"))),
            ),
        )
        val restored = SyncProtocolCodec.decodeOperation(SyncProtocolCodec.encodeOperation(operation))

        assertEquals(
            CanonicalContentHash.hash(operation.payload),
            CanonicalContentHash.hash(restored.payload),
        )
    }

    @Test
    fun envelopeFieldsDoNotChangeDomainHash() {
        val domain = mapOf("content" to JsonValue.fromString("same"))
        val operation = operation(payload = domain)
        val restored = SyncProtocolCodec.decodeOperation(SyncProtocolCodec.encodeOperation(operation))

        assertEquals(CanonicalContentHash.hash(domain), CanonicalContentHash.hash(restored.payload))
    }

    private fun operation(
        operationType: SyncOperationType = SyncOperationType.UPSERT,
        version: Int = 3,
        syncDecision: SyncDecision = SyncDecision.SYNC,
        redacted: Boolean = false,
        payload: Map<String, JsonValue> = mapOf(
            "title" to JsonValue.fromString("Pump procedure"),
            "content" to JsonValue.fromString("Revision 4"),
        ),
    ) = SyncOperationRecord(
        recordId = recordId,
        operationId = SyncOperationId.generate(operationType, recordId, version),
        operationType = operationType,
        state = OutboxOperationState.PENDING,
        attempts = 0,
        lastError = null,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_001L,
        leaseUntil = null,
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
