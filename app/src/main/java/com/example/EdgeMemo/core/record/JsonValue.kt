package com.example.EdgeMemo.core.record

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Safe representation of JSON values supported in Qdrant payloads.
 * This mirrors the JSON types that qdrant-edge accepts via serde_json.
 */
sealed interface JsonValue {
    fun toJson(): Any

    companion object {
        fun fromString(value: String): JsonValue = JsonString(value)
        fun fromInt(value: Int): JsonValue = JsonNumber(value.toDouble())
        fun fromLong(value: Long): JsonValue = JsonNumber(value.toDouble())
        fun fromDouble(value: Double): JsonValue = JsonNumber(value)
        fun fromBoolean(value: Boolean): JsonValue = JsonBoolean(value)
        fun fromMap(value: Map<String, JsonValue>): JsonValue = JsonObject(value)
        fun fromList(value: List<JsonValue>): JsonValue = JsonArray(value)
        fun nullValue(): JsonValue = JsonNull

        fun parse(jsonString: String): JsonValue {
            val token = JSONTokener(jsonString).nextValue()
            return when (token) {
                is JSONObject -> parseObject(token)
                is JSONArray -> parseArray(token)
                is String -> JsonString(token)
                is Number -> JsonNumber(token.toDouble())
                is Boolean -> JsonBoolean(token)
                null -> JsonNull
                else -> throw IllegalArgumentException("Unsupported JSON type: ${token.javaClass}")
            }
        }

        private fun parseObject(obj: JSONObject): JsonObject {
            val map = mutableMapOf<String, JsonValue>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next() as String
                val value = obj.get(key)
                map[key] = when (value) {
                    is JSONObject -> parseObject(value)
                    is JSONArray -> parseArray(value)
                    is String -> JsonString(value)
                    is Number -> JsonNumber(value.toDouble())
                    is Boolean -> JsonBoolean(value)
                    null -> JsonNull
                    else -> throw IllegalArgumentException("Unsupported JSON value type for key $key: ${value.javaClass}")
                }
            }
            return JsonObject(map)
        }

        private fun parseArray(arr: JSONArray): JsonArray {
            val list = mutableListOf<JsonValue>()
            for (i in 0 until arr.length()) {
                val value = arr.get(i)
                list.add(when (value) {
                    is JSONObject -> parseObject(value)
                    is JSONArray -> parseArray(value)
                    is String -> JsonString(value)
                    is Number -> JsonNumber(value.toDouble())
                    is Boolean -> JsonBoolean(value)
                    null -> JsonNull
                    else -> throw IllegalArgumentException("Unsupported JSON array element type: ${value.javaClass}")
                })
            }
            return JsonArray(list)
        }
    }
}

data class JsonString(val value: String) : JsonValue {
    override fun toJson() = value
}

data class JsonNumber(val value: Double) : JsonValue {
    override fun toJson() = value
    val asInt: Int get() = value.toInt()
    val asLong: Long get() = value.toLong()
    val asDouble: Double get() = value
}

data class JsonBoolean(val value: Boolean) : JsonValue {
    override fun toJson() = value
}

data class JsonObject(val value: Map<String, JsonValue>) : JsonValue {
    override fun toJson(): Map<String, Any> = value.mapValues { it.value.toJson() }

    operator fun get(key: String): JsonValue? = value[key]

    fun getString(key: String): String? = value[key]?.getString()
    fun getInt(key: String): Int? = value[key]?.getInt()
    fun getLong(key: String): Long? = value[key]?.getLong()
    fun getDouble(key: String): Double? = value[key]?.getDouble()
    fun getBoolean(key: String): Boolean? = value[key]?.getBoolean()
    fun getObject(key: String): JsonObject? = value[key]?.let { it as? JsonObject }
    fun getArray(key: String): JsonArray? = value[key]?.let { it as? JsonArray }
    fun getStringList(key: String): List<String>? = value[key]?.getStringList()
    fun getStringMap(key: String): Map<String, String>? = value[key]?.getStringMap()
}

data class JsonArray(val value: List<JsonValue>) : JsonValue {
    override fun toJson(): List<Any> = value.map { it.toJson() }

    operator fun get(index: Int): JsonValue = value[index]
    fun getStringList(): List<String>? = value.mapNotNull { it.getString() }
}

object JsonNull : JsonValue {
    override fun toJson() = JSONObject.NULL
    fun getString(): String? = null
    fun getInt(): Int? = null
    fun getLong(): Long? = null
    fun getDouble(): Double? = null
    fun getBoolean(): Boolean? = null
    fun getStringList(): List<String>? = null
    fun getStringMap(): Map<String, String>? = null
}

/** Extension functions for JsonValue concrete types. */
fun JsonString.getString(): String = value
fun JsonString.getInt(): Int? = value.toIntOrNull()
fun JsonString.getLong(): Long? = value.toLongOrNull()
fun JsonString.getDouble(): Double? = value.toDoubleOrNull()
fun JsonString.getBoolean(): Boolean? = when (value.lowercase()) { "true" -> true; "false" -> false; else -> null }
fun JsonString.getStringList(): List<String>? = null
fun JsonString.getStringMap(): Map<String, String>? = null

fun JsonNumber.getString(): String? = null
fun JsonNumber.getInt(): Int = asInt
fun JsonNumber.getLong(): Long = asLong
fun JsonNumber.getDouble(): Double = asDouble
fun JsonNumber.getBoolean(): Boolean? = null
fun JsonNumber.getStringList(): List<String>? = null
fun JsonNumber.getStringMap(): Map<String, String>? = null

fun JsonBoolean.getString(): String? = null
fun JsonBoolean.getInt(): Int? = null
fun JsonBoolean.getLong(): Long? = null
fun JsonBoolean.getDouble(): Double? = null
fun JsonBoolean.getBoolean(): Boolean = value
fun JsonBoolean.getStringList(): List<String>? = null
fun JsonBoolean.getStringMap(): Map<String, String>? = null

fun JsonArray.getString(): String? = null
fun JsonArray.getInt(): Int? = null
fun JsonArray.getLong(): Long? = null
fun JsonArray.getDouble(): Double? = null
fun JsonArray.getBoolean(): Boolean? = null
fun JsonArray.getStringList(): List<String>? = value.mapNotNull { it.getString() }
fun JsonArray.getStringMap(): Map<String, String>? = null