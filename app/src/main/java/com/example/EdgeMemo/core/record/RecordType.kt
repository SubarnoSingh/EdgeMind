package com.example.EdgeMemo.core.record

/**
 * Record type discriminator stored as a keyword in the payload.
 * Used to distinguish knowledge records from system records.
 */
enum class RecordType(
    val payloadValue: String,
) {
    MACHINE("machine"),
    MAINTENANCE_RECORD("maintenance_record"),
    FAILURE("failure"),
    PROCEDURE("procedure"),
    PART("part"),
    INSPECTION("inspection"),
    DOCUMENT("document"),
    DOCUMENT_CHUNK("document_chunk"),
    TECHNICIAN("technician"),
    LOCATION("location"),
    MEMORY("memory"),
    OUTBOX_OP("outbox_op"),
    CONFLICT("conflict"),
    SYS_CURSOR("sys_cursor"),
    SYS_SETTING("sys_setting"),
    SYS_ACTIVITY("sys_activity");  // semicolon required before companion object

    companion object {
        val KNOWLEDGE_TYPES = setOf(
            MACHINE, MAINTENANCE_RECORD, FAILURE, PROCEDURE, PART,
            INSPECTION, DOCUMENT, DOCUMENT_CHUNK, TECHNICIAN, LOCATION, MEMORY
        )
        val SYSTEM_TYPES = setOf(
            OUTBOX_OP, CONFLICT, SYS_CURSOR, SYS_SETTING, SYS_ACTIVITY
        )

        fun fromPayloadValue(value: String): RecordType? = values().firstOrNull { it.payloadValue == value }
    }
}