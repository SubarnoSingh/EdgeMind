package com.example.EdgeMemo.data.local.room

import androidx.room.TypeConverter

object MemoryTypeConverters {
    private const val ITEM_SEPARATOR = "\u001f"
    private const val ENTRY_SEPARATOR = "\u001e"

    @JvmStatic
    @TypeConverter
    fun listToString(value: List<String>): String = value.joinToString(ITEM_SEPARATOR)

    @JvmStatic
    @TypeConverter
    fun stringToList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(ITEM_SEPARATOR)

    @JvmStatic
    @TypeConverter
    fun mapToString(value: Map<String, String>): String =
        value.entries.joinToString(ENTRY_SEPARATOR) { "${it.key}$ITEM_SEPARATOR${it.value}" }

    @JvmStatic
    @TypeConverter
    fun stringToMap(value: String): Map<String, String> =
        if (value.isEmpty()) {
            emptyMap()
        } else {
            value.split(ENTRY_SEPARATOR).associate { entry ->
                val parts = entry.split(ITEM_SEPARATOR, limit = 2)
                parts[0] to parts.getOrElse(1) { "" }
            }
        }
}