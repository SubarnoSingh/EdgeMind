package com.example.EdgeMemo.core.sync

import com.example.EdgeMemo.core.record.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalContentHashTest {
    @Test
    fun objectKeyOrderDoesNotChangeHash() {
        val first = mapOf(
            "title" to JsonValue.fromString("Pump"),
            "details" to JsonValue.fromMap(
                mapOf(
                    "b" to JsonValue.fromInt(2),
                    "a" to JsonValue.fromInt(1),
                ),
            ),
        )
        val second = mapOf(
            "details" to JsonValue.fromMap(
                mapOf(
                    "a" to JsonValue.fromInt(1),
                    "b" to JsonValue.fromInt(2),
                ),
            ),
            "title" to JsonValue.fromString("Pump"),
        )

        assertEquals(CanonicalContentHash.hash(first), CanonicalContentHash.hash(second))
    }

    @Test
    fun equalPayloadsHaveEqualHashes() {
        val payload = mapOf("content" to JsonValue.fromString("same"))
        assertEquals(CanonicalContentHash.hash(payload), CanonicalContentHash.hash(payload.toMap()))
    }

    @Test
    fun envelopeFieldsAreExcluded() {
        val domain = mapOf("content" to JsonValue.fromString("same"))
        val withEnvelope = domain + mapOf(
            "_version" to JsonValue.fromInt(99),
            "_sync_state" to JsonValue.fromString("SYNCED"),
            "_last_synced_version" to JsonValue.fromInt(99),
            "_updated_at" to JsonValue.fromLong(100),
            "_created_at" to JsonValue.fromLong(1),
        )

        assertEquals(CanonicalContentHash.hash(domain), CanonicalContentHash.hash(withEnvelope))
    }

    @Test
    fun timestampsOperationIdsDeviceIdsAndVectorsAreExcluded() {
        val domain = mapOf("content" to JsonValue.fromString("same"))
        val transportFields = domain + mapOf(
            "created_at" to JsonValue.fromLong(1),
            "updatedAt" to JsonValue.fromLong(2),
            "timestamp" to JsonValue.fromLong(3),
            "operation_id" to JsonValue.fromString("UPSERT:ignored:1"),
            "device_id" to JsonValue.fromString("device-a"),
            "vectors" to JsonValue.fromList(listOf(JsonValue.fromDouble(1.0))),
        )

        assertEquals(CanonicalContentHash.hash(domain), CanonicalContentHash.hash(transportFields))
    }

    @Test
    fun changingOneDomainFieldChangesHash() {
        val first = mapOf("content" to JsonValue.fromString("before"))
        val second = mapOf("content" to JsonValue.fromString("after"))

        assertNotEquals(CanonicalContentHash.hash(first), CanonicalContentHash.hash(second))
    }

    @Test
    fun hashIsLowercaseSha256Hex() {
        val hash = CanonicalContentHash.hash(mapOf("content" to JsonValue.fromString("value")))

        assertEquals("66dfb862fd9d486482a9e0bd438f43ccc3281a8bb880c57a42fd0c0f736ce926", hash)
        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun transportFieldAliasesAreExcludedConsistently() {
        val domain = mapOf("content" to JsonValue.fromString("same"))
        val transportFields = domain + mapOf(
            "operationID" to JsonValue.fromString("op"),
            "deviceID" to JsonValue.fromString("device"),
            "lastSyncedVersion" to JsonValue.fromInt(5),
            "created-at" to JsonValue.fromLong(1),
            "updated-at" to JsonValue.fromLong(2),
        )

        assertEquals(CanonicalContentHash.hash(domain), CanonicalContentHash.hash(transportFields))
    }
}
