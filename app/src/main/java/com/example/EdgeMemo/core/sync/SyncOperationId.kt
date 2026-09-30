package com.example.EdgeMemo.core.sync

import com.example.EdgeMemo.core.record.RecordId

/**
 * The deterministic identity of one synchronization intent.
 *
 * An operation is identified by its type, logical record UUID, and record
 * version at enqueue time. It is deliberately not generated from a random
 * UUID or a process-local counter.
 */
@JvmInline
value class SyncOperationId private constructor(val value: String) {

    init {
        validateCanonical(value)
    }

    override fun toString(): String = value

    companion object {
        /** Build the canonical `<TYPE>:<record UUID>:<version>` identity. */
        @JvmStatic
        fun generate(
            operationType: SyncOperationType,
            recordId: RecordId,
            version: Int,
        ): SyncOperationId {
            require(version >= 1) { "Operation version must be at least 1" }
            return SyncOperationId("${operationType.name}:${recordId.uuid}:$version")
        }

        /** Alias for callers that prefer factory terminology. */
        @JvmStatic
        fun from(
            operationType: SyncOperationType,
            recordId: RecordId,
            version: Int,
        ): SyncOperationId = generate(operationType, recordId, version)

        /** Parse and validate a canonical operation identity. */
        @JvmStatic
        fun parse(raw: String): SyncOperationId {
            val segments = raw.split(':')
            require(segments.size == 3) {
                "Sync operation id must contain exactly three segments"
            }

            val operationType = try {
                SyncOperationType.valueOf(segments[0])
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Unknown sync operation type: ${segments[0]}")
            }
            val recordId = try {
                RecordId.fromString(segments[1])
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid record UUID in sync operation id", error)
            }
            val version = segments[2].toIntOrNull()
                ?: throw IllegalArgumentException("Invalid record version in sync operation id")
            require(version >= 1) { "Operation version must be at least 1" }
            require(segments[2] == version.toString()) {
                "Sync operation id is not in canonical format"
            }

            return generate(operationType, recordId, version)
        }

        /** Alias for [parse]. */
        @JvmStatic
        fun fromString(raw: String): SyncOperationId = parse(raw)
    }

    /** The parsed operation type, useful at domain boundaries. */
    val operationType: SyncOperationType
        get() = SyncOperationType.valueOf(value.substringBefore(':'))

    /** The parsed logical record id, useful at domain boundaries. */
    val recordId: RecordId
        get() = RecordId.fromString(value.split(':')[1])

    /** The parsed record version. */
    val version: Int
        get() = value.substringAfterLast(':').toInt()
}

private fun validateCanonical(value: String) {
    val segments = value.split(':')
    require(segments.size == 3) {
        "Sync operation id must contain exactly three segments"
    }
    val operationType = try {
        SyncOperationType.valueOf(segments[0])
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Unknown sync operation type: ${segments[0]}")
    }
    val recordId = RecordId.fromString(segments[1])
    val version = segments[2].toIntOrNull()
        ?: throw IllegalArgumentException("Invalid record version in sync operation id")
    require(version >= 1) { "Operation version must be at least 1" }
    require(segments[2] == version.toString()) {
        "Sync operation id is not in canonical format"
    }
    require(value == "${operationType.name}:${recordId.uuid}:$version") {
        "Sync operation id is not canonical"
    }
}
