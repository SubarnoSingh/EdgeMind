package com.example.EdgeMemo.core.sync

import com.example.EdgeMemo.core.record.JsonArray
import com.example.EdgeMemo.core.record.JsonBoolean
import com.example.EdgeMemo.core.record.JsonNull
import com.example.EdgeMemo.core.record.JsonNumber
import com.example.EdgeMemo.core.record.JsonObject
import com.example.EdgeMemo.core.record.JsonString
import com.example.EdgeMemo.core.record.JsonValue
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * SHA-256 of a deterministic serialization of a domain payload.
 *
 * The root object is sorted by key and envelope/transport fields are omitted.
 * Nested objects are sorted recursively, while array order remains semantic
 * and is therefore preserved. No Qdrant, Room, Android, or network work is
 * performed here.
 */
object CanonicalContentHash {
    /** Hash only the domain payload, excluding the sync envelope. */
    @JvmStatic
    fun hash(payload: Map<String, JsonValue>): String {
        val canonical = canonicalize(payload)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /** Alias for [hash]. */
    @JvmStatic
    fun compute(payload: Map<String, JsonValue>): String = hash(payload)

    /** Alias useful when the hash is treated as a factory result. */
    @JvmStatic
    fun of(payload: Map<String, JsonValue>): String = hash(payload)

    /** Exposed for tests and diagnostics; this is not a wire codec. */
    @JvmStatic
    fun canonicalize(payload: Map<String, JsonValue>): String =
        serializeObject(payload, root = true)

    private fun serialize(value: JsonValue): String = when (value) {
        is JsonString -> quote(value.value)
        is JsonNumber -> serializeNumber(value.value)
        is JsonBoolean -> if (value.value) "true" else "false"
        JsonNull -> "null"
        is JsonArray -> value.value.joinToString(",", prefix = "[", postfix = "]", transform = ::serialize)
        is JsonObject -> serializeObject(value.value, root = false)
    }

    private fun serializeObject(
        values: Map<String, JsonValue>,
        root: Boolean,
    ): String {
        val entries = values.asSequence()
            .filterNot { (key, _) -> root && isExcludedRootKey(key) }
            .sortedBy { (key, _) -> key }
            .joinToString(",", prefix = "{", postfix = "}") { (key, value) ->
                "${quote(key)}:${serialize(value)}"
            }
        return entries
    }

    private fun isExcludedRootKey(key: String): Boolean {
        if (key.startsWith("_")) return true
        val normalized = key.filter(Char::isLetterOrDigit).lowercase()
        return normalized in setOf(
            "vector",
            "vectors",
            "operationid",
            "operationids",
            "opid",
            "deviceid",
            "deviceids",
            "timestamp",
            "timestamps",
            "createdat",
            "updatedat",
        ) || normalized.startsWith("lastsynced")
    }

    private fun serializeNumber(value: Double): String {
        require(value.isFinite()) { "JSON numbers must be finite" }
        if (value == 0.0) return "0"
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
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
}

/** Convenience top-level form for callers that do not need the object name. */
fun canonicalContentHash(payload: Map<String, JsonValue>): String =
    CanonicalContentHash.hash(payload)
