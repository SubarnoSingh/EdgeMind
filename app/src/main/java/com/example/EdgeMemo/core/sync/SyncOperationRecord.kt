package com.example.EdgeMemo.core.sync

import com.example.EdgeMemo.core.record.JsonBoolean
import com.example.EdgeMemo.core.record.JsonNull
import com.example.EdgeMemo.core.record.JsonNumber
import com.example.EdgeMemo.core.record.JsonString
import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.SyncDecision

/**
 * Pure domain representation of a Qdrant-native outbox operation.
 *
 * This is intentionally not a Room entity and does not encode JSON for the
 * wire. [toEnvelopeMap] and [fromEnvelopeMap] only map the domain object to
 * the payload fields described by Phase 12 §20.2.
 */
data class SyncOperationRecord(
    val recordId: RecordId,
    val operationId: SyncOperationId,
    val operationType: SyncOperationType,
    val state: OutboxOperationState,
    val attempts: Int,
    val lastError: SyncFailureKind?,
    val createdAt: Long,
    val updatedAt: Long,
    val leaseUntil: Long?,
    val version: Int,
    val syncDecision: SyncDecision,
    val redacted: Boolean,
    /** Highest record version the cloud acknowledged for this operation (§7.3). */
    val lastSyncedVersion: Int? = null,
    val payload: Map<String, JsonValue>,
) {
    init {
        require(attempts >= 0) { "Operation attempts cannot be negative" }
        require(createdAt >= 0) { "Operation createdAt cannot be negative" }
        require(updatedAt >= 0) { "Operation updatedAt cannot be negative" }
        require(leaseUntil == null || leaseUntil >= 0) { "Operation leaseUntil cannot be negative" }
        require(version >= 1) { "Operation version must be at least 1" }
        require(lastSyncedVersion == null || lastSyncedVersion >= 1) {
            "lastSyncedVersion must be at least 1 when present"
        }
        require(syncDecision != SyncDecision.LOCAL_ONLY) {
            "LOCAL_ONLY records must never have a sync operation"
        }
        require(syncDecision != SyncDecision.SYNC_REDACTED || redacted) {
            "SYNC_REDACTED operations must carry a redacted payload"
        }
        require(operationId == SyncOperationId.generate(operationType, recordId, version)) {
            "Operation id does not match operation type, record id, and version"
        }
        require(payload.keys.none { it.startsWith("_") || it in ENVELOPE_KEYS }) {
            "Operation payload contains a reserved envelope field"
        }
    }

    /** Full Qdrant payload mapping, including the operation envelope. */
    fun toEnvelopeMap(): Map<String, JsonValue> {
        val result = linkedMapOf<String, JsonValue>()
        result[RECORD_TYPE] = JsonValue.fromString(RECORD_TYPE_VALUE)
        result[OPERATION_ID] = JsonValue.fromString(operationId.value)
        result[RECORD_ID] = JsonValue.fromString(recordId.uuid)
        result[OPERATION_TYPE] = JsonValue.fromString(operationType.name)
        result[STATE] = JsonValue.fromString(state.name)
        result[ATTEMPTS] = JsonValue.fromInt(attempts)
        lastError?.let { result[LAST_ERROR] = JsonValue.fromString(it.name) }
        result[CREATED_AT] = JsonValue.fromLong(createdAt)
        result[UPDATED_AT] = JsonValue.fromLong(updatedAt)
        leaseUntil?.let { result[LEASE_UNTIL] = JsonValue.fromLong(it) }
        result[VERSION] = JsonValue.fromInt(version)
        result[SYNC_DECISION] = JsonValue.fromString(syncDecision.name)
        result[REDACTED] = JsonValue.fromBoolean(redacted)
        lastSyncedVersion?.let { result[LAST_SYNCED_VERSION] = JsonValue.fromInt(it) }
        result.putAll(payload)
        return result
    }

    /** Alias emphasizing that this is a domain map, not JSON wire encoding. */
    fun toMap(): Map<String, JsonValue> = toEnvelopeMap()

    /** Alias for callers that use Qdrant's payload terminology. */
    fun toPayloadMap(): Map<String, JsonValue> = toEnvelopeMap()

    companion object {
        private const val RECORD_TYPE = "_record_type"
        private const val RECORD_TYPE_VALUE = "outbox_op"
        private const val OPERATION_ID = "operation_id"
        private const val RECORD_ID = "_record_id"
        private const val OPERATION_TYPE = "_operation_type"
        private const val STATE = "_state"
        private const val ATTEMPTS = "_attempts"
        private const val LAST_ERROR = "_last_error"
        private const val CREATED_AT = "_created_at"
        private const val UPDATED_AT = "_updated_at"
        private const val LEASE_UNTIL = "_lease_until"
        private const val VERSION = "_version"
        private const val SYNC_DECISION = "_sync_decision"
        private const val REDACTED = "_redacted"
        private const val LAST_SYNCED_VERSION = "_last_synced_version"

        private val ENVELOPE_KEYS = setOf(
            RECORD_TYPE,
            OPERATION_ID,
            RECORD_ID,
            OPERATION_TYPE,
            STATE,
            ATTEMPTS,
            LAST_ERROR,
            CREATED_AT,
            UPDATED_AT,
            LEASE_UNTIL,
            VERSION,
            SYNC_DECISION,
            REDACTED,
            LAST_SYNCED_VERSION,
        )

        /** Reconstruct from the domain map produced by [toEnvelopeMap]. */
        @JvmStatic
        fun fromEnvelopeMap(map: Map<String, JsonValue>): SyncOperationRecord {
            require(map.optionalString(RECORD_TYPE) == RECORD_TYPE_VALUE) {
                "Missing or invalid $RECORD_TYPE"
            }
            val operationId = SyncOperationId.parse(map.requiredString(OPERATION_ID))
            val recordId = RecordId.fromString(map.requiredString(RECORD_ID))
            val operationType = parseEnum<SyncOperationType>(
                map.requiredString(OPERATION_TYPE),
                OPERATION_TYPE,
            )
            val state = parseEnum<OutboxOperationState>(map.requiredString(STATE), STATE)
            val attempts = map.requiredInt(ATTEMPTS)
            val lastError = map.optionalString(LAST_ERROR)?.let {
                parseEnum<SyncFailureKind>(it, LAST_ERROR)
            }
            val createdAt = map.requiredLong(CREATED_AT)
            val updatedAt = map.requiredLong(UPDATED_AT)
            val leaseUntil = map.optionalLong(LEASE_UNTIL)
            val version = map.requiredInt(VERSION)
            val syncDecision = parseEnum<SyncDecision>(
                map.requiredString(SYNC_DECISION),
                SYNC_DECISION,
            )
            val redacted = map.requiredBoolean(REDACTED)
            val lastSyncedVersion = map.optionalInt(LAST_SYNCED_VERSION)
            val payload = map.filterKeys { it !in ENVELOPE_KEYS }
            return SyncOperationRecord(
                recordId = recordId,
                operationId = operationId,
                operationType = operationType,
                state = state,
                attempts = attempts,
                lastError = lastError,
                createdAt = createdAt,
                updatedAt = updatedAt,
                leaseUntil = leaseUntil,
                version = version,
                syncDecision = syncDecision,
                redacted = redacted,
                lastSyncedVersion = lastSyncedVersion,
                payload = payload,
            )
        }

        /** Alias for [fromEnvelopeMap]. */
        @JvmStatic
        fun fromMap(map: Map<String, JsonValue>): SyncOperationRecord = fromEnvelopeMap(map)

        private inline fun <reified T : Enum<T>> parseEnum(value: String, field: String): T =
            try {
                enumValueOf<T>(value)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid $field: $value")
            }
    }
}

private fun Map<String, JsonValue>.requiredValue(field: String): JsonValue =
    this[field] ?: throw IllegalArgumentException("Missing required field: $field")

private fun Map<String, JsonValue>.requiredString(field: String): String =
    requiredValue(field).asString(field)

private fun Map<String, JsonValue>.optionalString(field: String): String? =
    this[field]?.let { if (it == JsonNull) null else it.asString(field) }

private fun Map<String, JsonValue>.optionalInt(field: String): Int? =
    this[field]?.let { if (it == JsonNull) null else it.requiredIntFrom(field) }

private fun JsonValue.requiredIntFrom(field: String): Int {
    val number = asNumber(field)
    require(number == number.toInt().toDouble()) { "Field $field must be an integer" }
    return number.toInt()
}

private fun Map<String, JsonValue>.requiredInt(field: String): Int =
    requiredValue(field).asNumber(field).toInt().also {
        require(it.toDouble() == requiredValue(field).asNumber(field)) {
            "Field $field must be an integer"
        }
    }

private fun Map<String, JsonValue>.requiredLong(field: String): Long =
    requiredValue(field).asIntegralLong(field)

private fun Map<String, JsonValue>.optionalLong(field: String): Long? =
    this[field]?.let { if (it == JsonNull) null else it.asIntegralLong(field) }

private fun Map<String, JsonValue>.requiredBoolean(field: String): Boolean =
    when (val value = requiredValue(field)) {
        is JsonBoolean -> value.value
        else -> throw IllegalArgumentException("Field $field must be boolean")
    }

private fun JsonValue.asString(field: String): String = when (this) {
    is JsonString -> value
    else -> throw IllegalArgumentException("Field $field must be a string")
}

private fun JsonValue.asNumber(field: String): Double = when (this) {
    is JsonNumber -> value
    else -> throw IllegalArgumentException("Field $field must be a number")
}

private fun JsonValue.asIntegralLong(field: String): Long {
    val number = asNumber(field)
    require(number.isFinite()) { "Field $field must be finite" }
    val result = number.toLong()
    require(result.toDouble() == number) {
        "Field $field must be an integer"
    }
    return result
}
