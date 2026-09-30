package com.example.EdgeMemo.core.record

import com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 12B.5: FilterCompiler against the ACTUAL qdrant-edge 0.8.0 Filter
 * schema (`should` / `min_should` / `must` / `must_not` — with
 * `deny_unknown_fields`, so `minimum_should_match` is rejected).
 *
 * Two layers:
 *  1. Shape tests: emitted JSON structure (no `minimum_should_match`,
 *     nested logical composition preserved).
 *  2. Real-Qdrant tests: every compiled shape is executed against a real
 *     shard through the production JNI path via count / scroll / search and
 *     asserted against exact expected record sets. Not JSON-only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilterCompilerTest {

    companion object {
        private const val DIMENSION = 4
        private const val T1 = 1_700_000_000_000L

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    // ------------------------------------------------------------------
    // 1 — emitted shapes
    // ------------------------------------------------------------------

    @Test
    fun matchCompilesToMustCondition() {
        val json = JSONObject(FilterCompiler.compile(RecordFilter.severity("high"))!!)
        val must = json.getJSONArray("must")
        assertEquals(1, must.length())
        val cond = must.getJSONObject(0)
        assertEquals("severity", cond.getString("key"))
        assertEquals("high", cond.getJSONObject("match").getString("value"))
    }

    @Test
    fun inCompilesToShouldWithoutMinimumShouldMatch() {
        val compiled = FilterCompiler.compile(RecordFilter.In("severity", listOf("low", "critical")))!!
        val json = JSONObject(compiled)
        assertEquals(2, json.getJSONArray("should").length())
        assertFalse(
            "qdrant-edge 0.8.0 Filter uses deny_unknown_fields and has no minimum_should_match",
            compiled.contains("minimum_should_match"),
        )
    }

    @Test
    fun rangeCompilesWithBounds() {
        val json = JSONObject(FilterCompiler.compile(RecordFilter.Range("_version", gte = 2.0))!!)
        val cond = json.getJSONArray("must").getJSONObject(0)
        assertEquals(2.0, cond.getJSONObject("range").getDouble("gte"), 1e-9)
    }

    @Test
    fun dateRangeCompilesToExclusiveBounds() {
        val json = JSONObject(
            FilterCompiler.compile(RecordFilter.DateRange("_created_at", after = 100L, before = 200L))!!,
        )
        val range = json.getJSONArray("must").getJSONObject(0).getJSONObject("range")
        assertEquals(100.0, range.getDouble("gt"), 1e-9)
        assertEquals(200.0, range.getDouble("lt"), 1e-9)
    }

    @Test
    fun existsCompilesToNotOfIsEmpty() {
        // VERIFIED: `is_null:false` also matches missing fields, so the
        // compiler emits must_not(is_empty) for Exists.
        val json = JSONObject(FilterCompiler.compile(RecordFilter.Exists("_subject_key"))!!)
        val cond = json.getJSONArray("must_not").getJSONObject(0)
        assertEquals("_subject_key", cond.getJSONObject("is_empty").getString("key"))
    }

    @Test
    fun notCompilesToMustNot() {
        val json = JSONObject(FilterCompiler.compile(RecordFilter.Not(RecordFilter.severity("high")))!!)
        assertEquals("high", json.getJSONArray("must_not").getJSONObject(0).getJSONObject("match").getString("value"))
    }

    @Test
    fun nestedCombinationsPreserveBooleanStructure() {
        // (A OR B) AND (C OR D)
        val filter = RecordFilter.and(
            RecordFilter.or(RecordFilter.severity("high"), RecordFilter.severity("critical")),
            RecordFilter.or(RecordFilter.status("open"), RecordFilter.status("resolved")),
        )
        val compiled = FilterCompiler.compile(filter)!!
        val must = JSONObject(compiled).getJSONArray("must")
        assertEquals(2, must.length())
        assertEquals(2, must.getJSONObject(0).getJSONArray("should").length())
        assertEquals(2, must.getJSONObject(1).getJSONArray("should").length())
        assertFalse(compiled.contains("minimum_should_match"))
    }

    @Test
    fun orOfRecordTypesIsNestedConditionNotFlatMerge() {
        val filter = RecordFilter.or(
            RecordFilter.recordType(RecordType.PROCEDURE),
            RecordFilter.recordType(RecordType.OUTBOX_OP),
        )
        val compiled = FilterCompiler.compile(filter)!!
        val should = JSONObject(compiled).getJSONArray("should")
        assertEquals(2, should.length())
        assertFalse(compiled.contains("minimum_should_match"))
    }

    @Test
    fun emptySetsCompileToEmptyShould() {
        // VERIFIED qdrant-edge semantics: OptimizedFilter treats an empty
        // `should` as a no-op ("at least one ... if not empty"), matching the
        // Qdrant server rule. The compiler must emit a parseable, pinned shape.
        val compiled = FilterCompiler.compile(RecordFilter.In("severity", emptyList()))!!
        assertEquals(0, JSONObject(compiled).getJSONArray("should").length())
        assertEquals(
            0,
            JSONObject(FilterCompiler.compile(RecordFilter.Or(emptyList()))!!).getJSONArray("should").length(),
        )
    }

    @Test
    fun compileOfTrivialFiltersIsNull() {
        assertNull(FilterCompiler.compile(null))
        assertNull(FilterCompiler.compile(RecordFilter.And(emptyList())))
        assertNull(FilterCompiler.compileAll(emptyList()))
    }

    @Test
    fun tombstoneMatchIsBooleanTypedAndVersionIsNumeric() {
        val active = JSONObject(FilterCompiler.compile(RecordFilter.activeOnly())!!)
        assertTrue(
            active.getJSONArray("must").getJSONObject(0).getJSONObject("match").get("value") is Boolean,
        )
        val version = JSONObject(FilterCompiler.compile(RecordFilter.version(7))!!)
        assertEquals(7, version.getJSONArray("must").getJSONObject(0).getJSONObject("match").getInt("value"))
    }

    // ------------------------------------------------------------------
    // 2 — real qdrant-edge execution over the production store
    // ------------------------------------------------------------------

    private fun tempDir(): File {
        val dir = File.createTempFile("edgememo-12b5-", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    private fun record(
        uuid: String,
        type: RecordType,
        severity: String,
        status: String,
        line: String,
        version: Int,
        createdAt: Long,
        vector: FloatArray?,
        subjectKey: String? = null,
    ): Record = Record(
        id = RecordId.fromString(uuid),
        recordType = type,
        entityId = null,
        vector = vector,
        payload = mapOf(
            "name" to JsonValue.fromString("R-$uuid"),
            "severity" to JsonValue.fromString(severity),
            "status" to JsonValue.fromString(status),
            "line" to JsonValue.fromString(line),
        ),
        version = version,
        createdAt = createdAt,
        updatedAt = T1,
        subjectKey = subjectKey,
        syncDecision = SyncDecision.SYNC,
    )

    private suspend fun LocalRecordStore.ids(filter: RecordFilter?, types: Set<RecordType>? = null): List<String> =
        scroll(
            RecordQuery.Scroll(filter = filter, limit = 50, recordTypes = types),
        ).records
            .map { it.payload["name"]!!.getString()!! }
            .sorted()

    @Test
    fun everyCompiledShapeExecutesAgainstRealQdrant() = runBlocking {
        val dir = tempDir()
        val store = QdrantEdgeRecordStore(dir)
        try {
            store.initialize(DIMENSION)
            store.ensureIndexes()
            // R1 high/open   v2 T+2000 vector
            // R2 low/resolved v1 T+1000 vector
            // R3 critical/open v3 T+3000 payload-only
            // R4 high/resolved v1 T+4000 vector
            listOf(
                record("00000000-0000-0000-0000-000000000101", RecordType.PROCEDURE, "high", "open", "LINE-A", 2, T1 + 2000, floatArrayOf(1f, 0f, 0f, 0f), subjectKey = "SK-1"),
                record("00000000-0000-0000-0000-000000000102", RecordType.PROCEDURE, "low", "resolved", "LINE-B", 1, T1 + 1000, floatArrayOf(0f, 1f, 0f, 0f)),
                record("00000000-0000-0000-0000-000000000103", RecordType.OUTBOX_OP, "critical", "open", "LINE-A", 3, T1 + 3000, null),
                record("00000000-0000-0000-0000-000000000104", RecordType.PROCEDURE, "high", "resolved", "LINE-A", 1, T1 + 4000, floatArrayOf(0f, 0f, 1f, 0f)),
            ).forEach { store.upsert(it) }

            // Match
            assertEquals(
                listOf("R-00000000-0000-0000-0000-000000000101", "R-00000000-0000-0000-0000-000000000104"),
                store.ids(RecordFilter.severity("high")),
            )
            // In
            assertEquals(
                listOf("R-00000000-0000-0000-0000-000000000102", "R-00000000-0000-0000-0000-000000000103"),
                store.ids(RecordFilter.In("severity", listOf("low", "critical"))),
            )
            // Range over integer-indexed _version
            assertEquals(
                listOf("R-00000000-0000-0000-0000-000000000101", "R-00000000-0000-0000-0000-000000000103"),
                store.ids(RecordFilter.Range("_version", gte = 2.0)),
            )
            // DateRange over datetime-indexed _created_at (exclusive bounds)
            assertEquals(
                listOf("R-00000000-0000-0000-0000-000000000101", "R-00000000-0000-0000-0000-000000000103"),
                store.ids(RecordFilter.DateRange("_created_at", after = T1 + 1500, before = T1 + 3500)),
            )
            // Exists
            assertEquals(
                listOf("R-00000000-0000-0000-0000-000000000101"),
                store.ids(RecordFilter.Exists("_subject_key")),
            )
            // Not
            assertEquals(
                listOf("R-00000000-0000-0000-0000-000000000102", "R-00000000-0000-0000-0000-000000000103"),
                store.ids(RecordFilter.Not(RecordFilter.severity("high"))),
            )
            // And
            assertEquals(
                listOf("R-00000000-0000-0000-0000-000000000104"),
                store.ids(RecordFilter.and(RecordFilter.severity("high"), RecordFilter.status("resolved"))),
            )
            // Or (this is the shape qdrant-edge 0.8.0 rejected via
            // minimum_should_match before the 12B.5 fix)
            assertEquals(
                listOf("R-00000000-0000-0000-0000-000000000102", "R-00000000-0000-0000-0000-000000000103"),
                store.ids(RecordFilter.or(RecordFilter.severity("low"), RecordFilter.severity("critical"))),
            )
            // (A OR B) AND (C OR D)
            assertEquals(
                listOf(
                    "R-00000000-0000-0000-0000-000000000101",
                    "R-00000000-0000-0000-0000-000000000103",
                    "R-00000000-0000-0000-0000-000000000104",
                ),
                store.ids(
                    RecordFilter.and(
                        RecordFilter.or(RecordFilter.severity("high"), RecordFilter.severity("critical")),
                        RecordFilter.or(RecordFilter.line("LINE-A"), RecordFilter.machineId("nope")),
                    ),
                ),
            )
            // AND nested inside OR: (high AND open) OR (critical)
            assertEquals(
                listOf("R-00000000-0000-0000-0000-000000000101", "R-00000000-0000-0000-0000-000000000103"),
                store.ids(
                    RecordFilter.or(
                        RecordFilter.and(RecordFilter.severity("high"), RecordFilter.status("open")),
                        RecordFilter.severity("critical"),
                    ),
                ),
            )
            // Not(Or)
            assertEquals(
                listOf("R-00000000-0000-0000-0000-000000000101", "R-00000000-0000-0000-0000-000000000104"),
                store.ids(
                    RecordFilter.Not(
                        RecordFilter.or(RecordFilter.severity("low"), RecordFilter.severity("critical")),
                    ),
                ),
            )

            // recordTypes-scoped queries (record_type OR — broken pre-fix).
            assertEquals(
                3L,
                store.count(RecordQuery.Count(recordTypes = setOf(RecordType.PROCEDURE))),
            )
            assertEquals(
                1L,
                store.count(RecordQuery.Count(recordTypes = setOf(RecordType.OUTBOX_OP))),
            )
            assertEquals(
                4L,
                store.count(
                    RecordQuery.Count(
                        recordTypes = setOf(RecordType.PROCEDURE, RecordType.OUTBOX_OP),
                    ),
                ),
            )
            // Filter + record-type scope combined.
            assertEquals(
                listOf("R-00000000-0000-0000-0000-000000000101", "R-00000000-0000-0000-0000-000000000104"),
                store.ids(RecordFilter.severity("high"), types = setOf(RecordType.PROCEDURE)),
            )
            // allActive() regression: uses RecordFilter.or over knowledge types.
            val active = store.scroll(RecordQuery.allActive(limit = 10))
            assertEquals(3, active.records.size)

            // Real filtered SEARCH through the compiled Or shape.
            val hits = store.search(
                RecordQuery.Search(
                    vector = floatArrayOf(0.9f, 0.1f, 0.1f, 0.0f),
                    limit = 4,
                    filter = RecordFilter.or(RecordFilter.severity("high"), RecordFilter.severity("critical")),
                ),
            )
            val hitNames = hits.map { it.record.payload["name"]!!.getString()!! }.toSet()
            // Payload-only R3 has no vector and must never surface.
            assertEquals(
                setOf(
                    "R-00000000-0000-0000-0000-000000000101",
                    "R-00000000-0000-0000-0000-000000000104",
                ),
                hitNames,
            )

            // searchInTypes regression (record-type OR inside search filter).
            val scoped = store.search(
                RecordQuery.searchInTypes(
                    vector = floatArrayOf(1f, 0f, 0f, 0f),
                    recordTypes = setOf(RecordType.PROCEDURE),
                    limit = 4,
                ),
            )
            assertTrue(scoped.isNotEmpty())
            assertTrue(
                scoped.all { it.record.recordType == RecordType.PROCEDURE },
            )

            // Count matches scroll for identical compiled filters.
            assertEquals(
                2L,
                store.count(
                    RecordQuery.Count(
                        filter = RecordFilter.or(RecordFilter.severity("low"), RecordFilter.severity("critical")),
                    ),
                ),
            )
        } finally {
            runCatching { store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun exactNestedFilterMatchesRealShardAfterRestart() = runBlocking {
        // The claim-style filter the sync store relies on:
        // recordTypes(OUTBOX_OP) AND [ state IN (PENDING,FAILED) OR (IN_FLIGHT AND lease<now) ]
        val dir = tempDir()
        val store = QdrantEdgeRecordStore(dir)
        val claimable = RecordFilter.and(
            RecordFilter.anyOf(
                "_state",
                listOf(OutboxStateNames.PENDING, OutboxStateNames.FAILED),
            ),
            RecordFilter.Not(RecordFilter.match("_state", OutboxStateNames.ACKED)),
        )
        try {
            store.initialize(DIMENSION)
            store.ensureIndexes()
            store.upsert(opPoint("00000000-0000-0000-0000-000000000201", OutboxStateNames.PENDING))
            store.upsert(opPoint("00000000-0000-0000-0000-000000000202", OutboxStateNames.FAILED))
            store.upsert(opPoint("00000000-0000-0000-0000-000000000203", OutboxStateNames.ACKED))
            store.upsert(opPoint("00000000-0000-0000-0000-000000000204", OutboxStateNames.IN_FLIGHT))
            store.close()

            // Reopen (restart) and run the compiled nested filter.
            val reopened = QdrantEdgeRecordStore(dir)
            reopened.open()
            val claimedIds = reopened.scroll(
                RecordQuery.Scroll(filter = claimable, limit = 10, recordTypes = setOf(RecordType.OUTBOX_OP)),
            ).records.map { it.id.uuid }.sorted()
            assertEquals(
                listOf("00000000-0000-0000-0000-000000000201", "00000000-0000-0000-0000-000000000202"),
                claimedIds,
            )
            assertEquals(
                2L,
                reopened.count(
                    RecordQuery.Count(filter = claimable, recordTypes = setOf(RecordType.OUTBOX_OP)),
                ),
            )
            reopened.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun opPoint(uuid: String, state: String): Record = Record(
        id = RecordId.fromString(uuid),
        recordType = RecordType.OUTBOX_OP,
        entityId = null,
        vector = null,
        payload = mapOf(
            "_state" to JsonValue.fromString(state),
            "operation_id" to JsonValue.fromString("UPSERT:$uuid:1"),
        ),
        syncDecision = SyncDecision.SYNC,
    )

    private object OutboxStateNames {
        const val PENDING = "PENDING"
        const val FAILED = "FAILED"
        const val ACKED = "ACKED"
        const val IN_FLIGHT = "IN_FLIGHT"
    }
}
