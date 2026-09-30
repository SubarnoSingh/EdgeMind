package com.example.EdgeMemo.core.sync

import com.example.EdgeMemo.core.record.JsonArray
import com.example.EdgeMemo.core.record.JsonBoolean
import com.example.EdgeMemo.core.record.JsonNull
import com.example.EdgeMemo.core.record.JsonNumber
import com.example.EdgeMemo.core.record.JsonObject
import com.example.EdgeMemo.core.record.JsonString
import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.RecordId

/** Machine-readable outcome of a Phase 12 sync write response. */
enum class SyncResponseStatus {
    APPLIED,
    DUPLICATE,
    STALE,
    CONFLICT,
}

/**
 * The additive response contract from Phase 12 §23.1.
 *
 * `DUPLICATE` is the protocol's idempotent/no-op result. `APPLIED` covers a
 * new record, a forward update, and an accepted tombstone; HTTP status codes
 * distinguish creation from an update at the transport boundary.
 */
data class SyncProtocolResponse(
    val operationId: SyncOperationId,
    val recordId: RecordId,
    val status: SyncResponseStatus,
    val cloudVersion: Int?,
    val cloudContentHash: String?,
    val cloudTombstone: Boolean?,
    val failureKind: SyncFailureKind? = null,
) {
    init {
        require(operationId.recordId == recordId) {
            "Response operation id does not belong to record id"
        }
        require(cloudVersion == null || cloudVersion >= 1) {
            "Cloud version must be at least 1"
        }
        require(cloudContentHash == null || cloudContentHash.matches(Regex("[0-9a-f]{64}"))) {
            "Cloud content hash must be lowercase SHA-256 hex"
        }
        if (status == SyncResponseStatus.STALE || status == SyncResponseStatus.CONFLICT) {
            require(cloudVersion != null && cloudContentHash != null) {
                "${status.name} responses require cloud version and content hash"
            }
        }
    }
}

/**
 * Deterministic, strict JSON codec for the Phase 12 operation envelope and
 * response. This is a wire codec, not a persistence adapter and not an
 * Android/network client.
 */
object SyncProtocolCodec {
    private const val OPERATION_ID = "operation_id"
    private const val RECORD_ID = "_record_id"
    private const val STATUS = "status"
    private const val CLOUD_VERSION = "cloudVersion"
    private const val CLOUD_CONTENT_HASH = "cloudContentHash"
    private const val CLOUD_TOMBSTONE = "cloudTombstone"
    private const val FAILURE_KIND = "failureKind"

    /** Encode the exact §20.2 envelope with lexicographically ordered keys. */
    @JvmStatic
    fun encodeOperation(operation: SyncOperationRecord): String =
        serializeObject(operation.toEnvelopeMap())

    /** Decode and validate the exact §20.2 envelope. */
    @JvmStatic
    fun decodeOperation(json: String): SyncOperationRecord {
        val root = parseStrictObject(json)
        return SyncOperationRecord.fromEnvelopeMap(root)
    }

    /** Encode the additive response fields defined by §23.1. */
    @JvmStatic
    fun encodeResponse(response: SyncProtocolResponse): String {
        val fields = linkedMapOf<String, JsonValue>(
            "operationId" to JsonValue.fromString(response.operationId.value),
            "memoryId" to JsonValue.fromString(response.recordId.uuid),
            STATUS to JsonValue.fromString(response.status.name),
        )
        response.cloudVersion?.let { fields[CLOUD_VERSION] = JsonValue.fromInt(it) }
        response.cloudContentHash?.let {
            fields[CLOUD_CONTENT_HASH] = JsonValue.fromString(it)
        }
        response.cloudTombstone?.let {
            fields[CLOUD_TOMBSTONE] = JsonValue.fromBoolean(it)
        }
        response.failureKind?.let { fields[FAILURE_KIND] = JsonValue.fromString(it.name) }
        return serializeObject(fields)
    }

    /** Decode and validate a response. Unknown status/enum values fail closed. */
    @JvmStatic
    fun decodeResponse(json: String): SyncProtocolResponse {
        val root = parseStrictObject(json)
        val operationId = root.requiredString("operationId").let(SyncOperationId::parse)
        val recordId = RecordId.fromString(root.requiredString("memoryId"))
        val status = parseEnum<SyncResponseStatus>(root.requiredString(STATUS), STATUS)
        val cloudVersion = root.optionalInt(CLOUD_VERSION)
        require(cloudVersion == null || cloudVersion >= 1) {
            "$CLOUD_VERSION must be at least 1"
        }
        val cloudContentHash = root.optionalString(CLOUD_CONTENT_HASH)
        val cloudTombstone = root.optionalBoolean(CLOUD_TOMBSTONE)
        val failureKind = root.optionalString(FAILURE_KIND)?.let {
            parseEnum<SyncFailureKind>(it, FAILURE_KIND)
        }
        return SyncProtocolResponse(
            operationId = operationId,
            recordId = recordId,
            status = status,
            cloudVersion = cloudVersion,
            cloudContentHash = cloudContentHash,
            cloudTombstone = cloudTombstone,
            failureKind = failureKind,
        )
    }

    private fun parseStrictObject(json: String): Map<String, JsonValue> {
        require(json.isNotBlank()) { "Protocol JSON must not be blank" }
        val value = try {
            StrictJsonParser(json).parse()
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Malformed protocol JSON", error)
        }
        require(value is JsonObject) { "Protocol root must be a JSON object" }
        return value.value
    }

    private fun serializeObject(values: Map<String, JsonValue>): String =
        values.asSequence()
            .sortedBy { it.key }
            .joinToString(",", prefix = "{", postfix = "}") { (key, value) ->
                "${quote(key)}:${serialize(value)}"
            }

    private fun serialize(value: JsonValue): String = when (value) {
        is JsonString -> quote(value.value)
        is JsonNumber -> {
            require(value.value.isFinite()) { "Protocol numbers must be finite" }
            require(value.value == value.value.toLong().toDouble() || value.value % 1.0 != 0.0) {
                "Protocol number is not representable"
            }
            value.value.toBigDecimal().stripTrailingZeros().toPlainString()
        }
        is JsonBoolean -> if (value.value) "true" else "false"
        JsonNull -> "null"
        is JsonArray -> value.value.joinToString(",", prefix = "[", postfix = "]", transform = ::serialize)
        is JsonObject -> serializeObject(value.value)
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                in '\u0000'..'\u001F' -> append("\\u%04x".format(character.code))
                else -> append(character)
            }
        }
        append('"')
    }

    private inline fun <reified T : Enum<T>> parseEnum(value: String, field: String): T =
        try {
            enumValueOf<T>(value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid $field: $value")
        }

    /** Small dependency-free JSON parser so JVM tests do not rely on Android's org.json stubs. */
    private class StrictJsonParser(private val input: String) {
        private var index = 0

        fun parse(): JsonValue {
            skipWhitespace()
            val result = parseValue()
            skipWhitespace()
            require(index == input.length) { "Trailing JSON data" }
            return result
        }

        private fun parseValue(): JsonValue {
            require(index < input.length) { "Unexpected end of JSON" }
            return when (input[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonString(parseString())
                't' -> { consume("true"); JsonBoolean(true) }
                'f' -> { consume("false"); JsonBoolean(false) }
                'n' -> { consume("null"); JsonNull }
                '-', in '0'..'9' -> JsonNumber(parseNumber())
                else -> throw IllegalArgumentException("Unexpected JSON character")
            }
        }

        private fun parseObject(): JsonObject {
            expect('{')
            skipWhitespace()
            val values = linkedMapOf<String, JsonValue>()
            if (peek('}')) {
                index++
                return JsonObject(values)
            }
            while (true) {
                skipWhitespace()
                require(index < input.length && input[index] == '"') { "Object key must be a string" }
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                require(values.put(key, parseValue()) == null) { "Duplicate JSON object key" }
                skipWhitespace()
                when {
                    peek('}') -> { index++; return JsonObject(values) }
                    peek(',') -> index++
                    else -> throw IllegalArgumentException("Expected object comma or end")
                }
            }
        }

        private fun parseArray(): JsonArray {
            expect('[')
            skipWhitespace()
            val values = mutableListOf<JsonValue>()
            if (peek(']')) {
                index++
                return JsonArray(values)
            }
            while (true) {
                skipWhitespace()
                values += parseValue()
                skipWhitespace()
                when {
                    peek(']') -> { index++; return JsonArray(values) }
                    peek(',') -> index++
                    else -> throw IllegalArgumentException("Expected array comma or end")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < input.length) {
                when (val character = input[index++]) {
                    '"' -> return result.toString()
                    '\\' -> {
                        require(index < input.length) { "Incomplete JSON escape" }
                        when (val escaped = input[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                require(index + 4 <= input.length) { "Incomplete unicode escape" }
                                val code = input.substring(index, index + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Invalid unicode escape")
                                result.append(code.toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("Invalid JSON escape")
                        }
                    }
                    else -> {
                        require(character.code >= 0x20) { "Control character in JSON string" }
                        result.append(character)
                    }
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun parseNumber(): Double {
            val start = index
            if (peek('-')) index++
            if (peek('0')) {
                index++
            } else {
                require(index < input.length && input[index] in '1'..'9') { "Invalid JSON number" }
                while (index < input.length && input[index].isDigit()) index++
            }
            if (peek('.')) {
                index++
                require(index < input.length && input[index].isDigit()) { "Invalid JSON fraction" }
                while (index < input.length && input[index].isDigit()) index++
            }
            if (peek('e') || peek('E')) {
                index++
                if (peek('+') || peek('-')) index++
                require(index < input.length && input[index].isDigit()) { "Invalid JSON exponent" }
                while (index < input.length && input[index].isDigit()) index++
            }
            val number = input.substring(start, index).toDouble()
            require(number.isFinite()) { "JSON number must be finite" }
            return number
        }

        private fun consume(expected: String) {
            require(input.regionMatches(index, expected, 0, expected.length)) { "Invalid JSON literal" }
            index += expected.length
        }

        private fun expect(character: Char) {
            require(peek(character)) { "Expected '$character'" }
            index++
        }

        private fun peek(character: Char): Boolean = index < input.length && input[index] == character

        private fun skipWhitespace() {
            while (index < input.length && input[index].isWhitespace()) index++
        }
    }
}

private fun Map<String, JsonValue>.requiredValue(field: String): JsonValue =
    this[field] ?: throw IllegalArgumentException("Missing required field: $field")

private fun Map<String, JsonValue>.requiredString(field: String): String =
    when (val value = requiredValue(field)) {
        is JsonString -> value.value
        else -> throw IllegalArgumentException("$field must be a string")
    }

private fun Map<String, JsonValue>.optionalString(field: String): String? =
    this[field]?.let {
        when (it) {
            JsonNull -> null
            is JsonString -> it.value
            else -> throw IllegalArgumentException("$field must be a string or null")
        }
    }

private fun Map<String, JsonValue>.optionalInt(field: String): Int? =
    this[field]?.let {
        if (it == JsonNull) return@let null
        val number = (it as? JsonNumber)?.value
            ?: throw IllegalArgumentException("$field must be an integer or null")
        require(number.isFinite() && number % 1.0 == 0.0) {
            "$field must be an integer"
        }
        val result = number.toInt()
        require(result.toDouble() == number) { "$field is out of range" }
        result
    }

private fun Map<String, JsonValue>.optionalBoolean(field: String): Boolean? =
    this[field]?.let {
        when (it) {
            JsonNull -> null
            is JsonBoolean -> it.value
            else -> throw IllegalArgumentException("$field must be a boolean or null")
        }
    }
