package com.example.EdgeMemo.core.record

import org.json.JSONArray
import org.json.JSONObject

/**
 * Compiles the typed record-filter algebra to qdrant-edge 0.8.0 filter JSON.
 *
 * qdrant-edge uses `should` for an any-of clause; it does not accept the
 * Elasticsearch-style `minimum_should_match` field. Nested logical clauses
 * are emitted as nested Filter conditions so that combinations such as
 * `(A OR B) AND (C OR D)` retain their boolean meaning.
 */
object FilterCompiler {

    /**
     * Compile a filter clause to a Qdrant Filter JSON object.
     * Returns null if the filter is empty/trivial.
     */
    fun compile(filter: RecordFilter?): String? {
        if (filter == null) return null
        val json = compileClause(filter)
        return if (json.length() == 0) null else json.toString()
    }

    /** Compile multiple top-level filters combined with AND. */
    fun compileAll(filters: List<RecordFilter>): String? {
        if (filters.isEmpty()) return null
        return JSONObject()
            .put("must", JSONArray(filters.map(::compileCondition)))
            .toString()
    }

    /** A Qdrant Filter object. A clause is represented as a condition in a parent. */
    private fun compileClause(clause: RecordFilter): JSONObject = when (clause) {
        is RecordFilter.Match -> JSONObject().put("must", JSONArray().put(fieldCondition(clause.field, clause.value)))
        is RecordFilter.In -> compileIn(clause)
        is RecordFilter.Range -> compileRange(clause)
        is RecordFilter.DateRange -> compileDateRange(clause)
        is RecordFilter.GeoBox -> compileGeoBox(clause)
        is RecordFilter.Exists -> compileExists(clause)
        is RecordFilter.Not -> compileNot(clause)
        is RecordFilter.And -> compileAnd(clause)
        is RecordFilter.Or -> compileOr(clause)
    }

    /** Convert a typed clause into one qdrant Condition object. */
    private fun compileCondition(clause: RecordFilter): JSONObject = when (clause) {
        is RecordFilter.Match -> fieldCondition(clause.field, clause.value)
        is RecordFilter.In -> compileIn(clause)
        is RecordFilter.Range -> rangeCondition(clause.field, clause.gt, clause.gte, clause.lt, clause.lte)
        is RecordFilter.DateRange -> rangeCondition(clause.field, clause.after?.toDouble(), null, null, clause.before?.toDouble())
        is RecordFilter.GeoBox -> geoCondition(clause)
        is RecordFilter.Exists -> existsCondition(clause.field)
        is RecordFilter.Not -> compileNot(clause)
        is RecordFilter.And -> compileAnd(clause)
        is RecordFilter.Or -> compileOr(clause)
    }

    /**
     * Field-exists condition. VERIFIED against qdrant-edge 0.8.0:
     * `is_null:false` ALSO matches points where the field is missing
     * (check_is_null iterates present values only). Existence is therefore
     * emitted as `must_not` of `is_empty`, a Filter shape valid both at the
     * top level and nested as a Condition (untagged Condition::Filter).
     */
    private fun existsCondition(field: String): JSONObject = JSONObject()
        .put("must_not", JSONArray().put(JSONObject().put("is_empty", JSONObject().put("key", field))))

    private fun fieldCondition(field: String, value: String): JSONObject {
        val typedValue: Any = when (field) {
            "_tombstone" -> value.toBooleanStrictOrNull() ?: value
            "_version", "_schema_version", "_attempts", "_last_synced_version" ->
                value.toLongOrNull() ?: value
            else -> value
        }
        return JSONObject()
            .put("key", field)
            .put("match", JSONObject().put("value", typedValue))
    }

    private fun compileIn(inFilter: RecordFilter.In): JSONObject {
        // VERIFIED against qdrant-edge 0.8.0: an empty `should` is a no-op
        // (OptimizedFilter: "at least one ... if not empty"), matching the
        // server rule. Emit the shape explicitly rather than `{}`.
        if (inFilter.values.isEmpty()) return emptyShould()
        return JSONObject().put(
            "should",
            JSONArray(inFilter.values.map { fieldCondition(inFilter.field, it) }),
        )
    }

    private fun emptyShould(): JSONObject = JSONObject().put("should", JSONArray())

    private fun rangeCondition(
        field: String,
        gt: Double?,
        gte: Double?,
        lt: Double?,
        lte: Double?,
    ): JSONObject {
        val range = JSONObject()
        gt?.let { range.put("gt", it) }
        gte?.let { range.put("gte", it) }
        lt?.let { range.put("lt", it) }
        lte?.let { range.put("lte", it) }
        return JSONObject().put("key", field).put("range", range)
    }

    private fun compileRange(range: RecordFilter.Range): JSONObject = JSONObject().put(
        "must",
        JSONArray().put(rangeCondition(range.field, range.gt, range.gte, range.lt, range.lte)),
    )

    private fun compileDateRange(dateRange: RecordFilter.DateRange): JSONObject = JSONObject().put(
        "must",
        JSONArray().put(
            rangeCondition(
                field = dateRange.field,
                gt = dateRange.after?.toDouble(),
                gte = null,
                lt = dateRange.before?.toDouble(),
                lte = null,
            ),
        ),
    )

    private fun geoCondition(geoBox: RecordFilter.GeoBox): JSONObject {
        val geo = JSONObject()
            .put("top_left", JSONObject().put("lat", geoBox.topLeft.lat).put("lon", geoBox.topLeft.lon))
            .put("bottom_right", JSONObject().put("lat", geoBox.bottomRight.lat).put("lon", geoBox.bottomRight.lon))
        return JSONObject().put("key", geoBox.field).put("geo_bounding_box", geo)
    }

    private fun compileGeoBox(geoBox: RecordFilter.GeoBox): JSONObject = JSONObject()
        .put("must", JSONArray().put(geoCondition(geoBox)))

    private fun compileExists(exists: RecordFilter.Exists): JSONObject =
        existsCondition(exists.field)

    private fun compileNot(not: RecordFilter.Not): JSONObject = JSONObject()
        .put("must_not", JSONArray().put(compileCondition(not.clause)))

    private fun compileAnd(and: RecordFilter.And): JSONObject {
        if (and.clauses.isEmpty()) return JSONObject()
        return JSONObject().put("must", JSONArray(and.clauses.map(::compileCondition)))
    }

    private fun compileOr(or: RecordFilter.Or): JSONObject {
        if (or.clauses.isEmpty()) return emptyShould()
        return JSONObject().put("should", JSONArray(or.clauses.map(::compileCondition)))
    }
}
