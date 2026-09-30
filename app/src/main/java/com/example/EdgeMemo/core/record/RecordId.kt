package com.example.EdgeMemo.core.record

import java.util.UUID
import java.util.regex.Pattern

/**
 * A UUID point ID. qdrant-edge accepts only u64 or UUID point IDs.
 * This wrapper validates UUID format at construction.
 */
@JvmInline
value class RecordId internal constructor(val uuid: String) {
    companion object {
        private val UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )

        fun fromString(uuid: String): RecordId {
            require(UUID_PATTERN.matcher(uuid).matches()) { "Invalid UUID format: $uuid" }
            return RecordId(uuid.lowercase())
        }

        fun random(): RecordId = RecordId(UUID.randomUUID().toString())
    }
}