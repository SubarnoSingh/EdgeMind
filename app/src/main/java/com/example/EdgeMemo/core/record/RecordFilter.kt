package com.example.EdgeMemo.core.record

/**
 * Typed filter abstraction for Qdrant payload queries.
 * Maps to qdrant-edge Filter JSON (must/should clauses with key/match/range).
 */
sealed interface RecordFilter {

    /** Equality match on a keyword field. */
    data class Match(
        val field: String,
        val value: String,
    ) : RecordFilter

    /** IN match on a keyword field (any of the values). */
    data class In(
        val field: String,
        val values: List<String>,
    ) : RecordFilter

    /** Numeric/date range filter. */
    data class Range(
        val field: String,
        val gt: Double? = null,
        val gte: Double? = null,
        val lt: Double? = null,
        val lte: Double? = null,
    ) : RecordFilter

    /** Date range filter (epoch millis). */
    data class DateRange(
        val field: String,
        val after: Long? = null,
        val before: Long? = null,
    ) : RecordFilter

    /** Geo bounding box filter. */
    data class GeoBox(
        val field: String,
        val topLeft: GeoPoint,
        val bottomRight: GeoPoint,
    ) : RecordFilter

    /** Field exists (is not null/missing). */
    data class Exists(
        val field: String,
    ) : RecordFilter

    /** Logical NOT of a clause. */
    data class Not(
        val clause: RecordFilter,
    ) : RecordFilter

    /** Logical AND of multiple clauses. */
    data class And(
        val clauses: List<RecordFilter>,
    ) : RecordFilter

    /** Logical OR of multiple clauses. */
    data class Or(
        val clauses: List<RecordFilter>,
    ) : RecordFilter

    companion object {
        /** Equality match on any keyword field. */
        fun match(field: String, value: String): Match = Match(field, value)

        /** Any-of match on a keyword field. */
        fun anyOf(field: String, values: List<String>): In = In(field, values)

        /** Match record_type exactly. */
        fun recordType(type: RecordType): Match = Match("_record_type", type.payloadValue)

        /** Match entity_id exactly. */
        fun entityId(id: String): Match = Match("_entity_id", id)

        /** Match tombstone = false (exclude soft-deleted). */
        fun activeOnly(): Match = Match("_tombstone", "false")

        /** Match tombstone = true (only soft-deleted). */
        fun deletedOnly(): Match = Match("_tombstone", "true")

        /** Match sync_state. */
        fun syncState(state: SyncState): Match = Match("_sync_state", state.name)

        /** Match sync_decision. */
        fun syncDecision(decision: SyncDecision): Match = Match("_sync_decision", decision.name)

        /** Match machine_id (domain field). */
        fun machineId(id: String): Match = Match("machine_id", id)

        /** Match severity. */
        fun severity(value: String): Match = Match("severity", value)

        /** Match status. */
        fun status(value: String): Match = Match("status", value)

        /** Match plant. */
        fun plant(value: String): Match = Match("plant", value)

        /** Match line. */
        fun line(value: String): Match = Match("line", value)

        /** Match zone. */
        fun zone(value: String): Match = Match("zone", value)

        /** Match failure_type. */
        fun failureType(value: String): Match = Match("failure_type", value)

        /** Match work_type. */
        fun workType(value: String): Match = Match("work_type", value)

        /** Match part_id. */
        fun partId(value: String): Match = Match("part_id", value)

        /** Match technician_id. */
        fun technicianId(value: String): Match = Match("technician_id", value)

        /** Match document_id. */
        fun documentId(value: String): Match = Match("document_id", value)

        /** Match procedure_id. */
        fun procedureId(value: String): Match = Match("procedure_id", value)

        /** Match subject_key. */
        fun subjectKey(key: String): Match = Match("_subject_key", key)

        /** Match version (numeric). */
        fun version(version: Int): Match = Match("_version", version.toString())

        /** Match created_at range. */
        fun createdAfter(epochMillis: Long): DateRange = DateRange("_created_at", after = epochMillis)

        fun createdBefore(epochMillis: Long): DateRange = DateRange("_created_at", before = epochMillis)

        /** Match updated_at range. */
        fun updatedAfter(epochMillis: Long): DateRange = DateRange("_updated_at", after = epochMillis)

        fun updatedBefore(epochMillis: Long): DateRange = DateRange("_updated_at", before = epochMillis)

        /** Combine multiple filters with AND. */
        fun and(vararg filters: RecordFilter): And = And(filters.toList())

        fun and(filters: List<RecordFilter>): And = And(filters)

        /** Combine multiple filters with OR. */
        fun or(vararg filters: RecordFilter): Or = Or(filters.toList())

        fun or(filters: List<RecordFilter>): Or = Or(filters)
    }
}

/** Geo point for geo bounding box filters. */
data class GeoPoint(
    val lat: Double,
    val lon: Double,
)