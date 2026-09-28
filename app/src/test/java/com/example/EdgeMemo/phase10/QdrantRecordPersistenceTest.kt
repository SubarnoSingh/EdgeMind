package com.example.EdgeMemo.phase10

import com.example.EdgeMemo.native.qdrant.NativeBridge
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase-10 spike: prove that structured industrial records (dense vector +
 * JSON payload) written through the real JNI → Rust → qdrant-edge path
 * survive:
 *
 *  - in-process close/reopen
 *  - a separate JVM process writing and exiting WITHOUT a graceful close
 *    (the closest reproducible stand-in for Android process death on a host
 *    with no device attached)
 *
 * and that payload filters, payload indexes, updates and deletes all behave
 * correctly after restart — with NO mirror state held anywhere outside the
 * Qdrant Edge shard. Everything asserted here is re-read from the shard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QdrantRecordPersistenceTest {

    companion object {
        private const val LIB_PROPERTY = "edgememo.qdrant.lib"
        private const val PHASE_PROPERTY = "edge.ph"
        private const val PERSIST_DIR_PROPERTY = "edge.persist.dir"
        private const val DIMENSION = 4

        const val CREATED_AT = 1_700_000_000_000L
        const val UPDATED_AT_V2 = 1_700_000_600_000L

        data class SpikeRecord(
            val pointId: String,
            val recordId: String,
            val machineId: String,
            val severity: String,
            val status: String,
            val plant: String,
            val line: String,
            val vector: FloatArray,
        )

        val RECORDS = listOf(
            SpikeRecord("00000000-0000-0000-0000-000000000001", "MR-001", "M-042", "high", "open", "Jamshedpur-02", "LINE-A", floatArrayOf(1f, 0f, 0f, 0f)),
            SpikeRecord("00000000-0000-0000-0000-000000000002", "MR-002", "M-042", "low", "resolved", "Jamshedpur-02", "LINE-B", floatArrayOf(0f, 1f, 0f, 0f)),
            SpikeRecord("00000000-0000-0000-0000-000000000003", "MR-003", "M-100", "high", "resolved", "Jamshedpur-03", "LINE-A", floatArrayOf(0f, 0f, 1f, 0f)),
            SpikeRecord("00000000-0000-0000-0000-000000000004", "MR-004", "M-200", "critical", "open", "Jamshedpur-02", "LINE-A", floatArrayOf(0f, 0f, 0f, 1f)),
        )

        val INDEX_FIELDS = listOf(
            "record_type" to "keyword",
            "machine_id" to "keyword",
            "severity" to "keyword",
            "status" to "keyword",
            "plant" to "keyword",
            "line" to "keyword",
        )

        fun severityHighFilter() = """{"must":[{"key":"severity","match":{"value":"high"}}]}"""
        fun statusResolvedFilter() = """{"must":[{"key":"status","match":{"value":"resolved"}}]}"""
        fun machine042Filter() = """{"must":[{"key":"machine_id","match":{"value":"M-042"}}]}"""
        fun combinedFilter() =
            """{"must":[{"key":"record_type","match":{"value":"maintenance_record"}},""" +
                """{"key":"severity","match":{"value":"high"}},""" +
                """{"key":"line","match":{"value":"LINE-A"}}]}"""
        fun combinedUniqueFilter() =
            """{"must":[{"key":"record_type","match":{"value":"maintenance_record"}},""" +
                """{"key":"severity","match":{"value":"high"}},""" +
                """{"key":"line","match":{"value":"LINE-A"}},""" +
                """{"key":"machine_id","match":{"value":"M-042"}}]}"""
        fun severityCriticalFilter() = """{"must":[{"key":"severity","match":{"value":"critical"}}]}"""
        fun versionOneFilter() = """{"must":[{"key":"version","match":{"value":1}}]}"""

        fun payload(record: SpikeRecord, version: Int = 1, updatedAt: Long = CREATED_AT): JSONObject =
            JSONObject().apply {
                put("record_type", "maintenance_record")
                put("id", record.recordId)
                put("version", version)
                put("created_at", CREATED_AT)
                put("updated_at", updatedAt)
                put("content", "Hydraulic pump overheating on ${record.line}")
                put("machine_id", record.machineId)
                put("severity", record.severity)
                put("status", record.status)
                put("plant", record.plant)
                put("line", record.line)
                put("zone", "PRESS-04")
            }

        /** Plain-JVM safe JSON literal (child processes do not rely on org.json). */
        fun payloadLiteral(record: SpikeRecord, version: Int = 1, updatedAt: Long = CREATED_AT): String =
            "{" +
                "\"record_type\":\"maintenance_record\"," +
                "\"id\":\"${record.recordId}\"," +
                "\"version\":$version," +
                "\"created_at\":$CREATED_AT," +
                "\"updated_at\":$updatedAt," +
                "\"content\":\"Hydraulic pump overheating on ${record.line}\"," +
                "\"machine_id\":\"${record.machineId}\"," +
                "\"severity\":\"${record.severity}\"," +
                "\"status\":\"${record.status}\"," +
                "\"plant\":\"${record.plant}\"," +
                "\"line\":\"${record.line}\"," +
                "\"zone\":\"PRESS-04\"" +
                "}"

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }

        @JvmStatic
        fun main(args: Array<String>) {
            val phase = args.firstOrNull() ?: System.getProperty(PHASE_PROPERTY)
                ?: throw IllegalArgumentException("phase (phase10crashwrite|phase10verify) required")
            val persistDir = File(
                System.getProperty(PERSIST_DIR_PROPERTY)
                    ?: throw IllegalArgumentException("$PERSIST_DIR_PROPERTY required"),
            )
            System.load(TestNativeLoader.nativeLibraryPath())
            when (phase) {
                // Simulates an Android process death: writes payload records +
                // indexes + flush, then the JVM exits WITHOUT any close/Drop.
                "phase10crashwrite" -> {
                    persistDir.mkdirs()
                    val handle = NativeBridge.nativeCreate(persistDir.absolutePath, DIMENSION)
                    for ((field, schema) in INDEX_FIELDS) {
                        NativeBridge.nativeCreatePayloadIndex(handle, field, schema)
                    }
                    for (record in RECORDS) {
                        NativeBridge.nativeUpsertWithPayload(
                            handle,
                            record.pointId,
                            record.vector,
                            payloadLiteral(record),
                        )
                    }
                    NativeBridge.nativeFlush(handle)
                    // No nativeClose — the JVM exit releases the directory
                    // lock without ever dropping the shard.
                }

                // A second, different JVM process re-reads the same path and
                // verifies payloads, vectors and filters — without recreating
                // any index.
                "phase10verify" -> {
                    val handle = NativeBridge.nativeOpen(persistDir.absolutePath)
                    val count = NativeBridge.nativeCountFiltered(handle, null, true)
                    val retrieved = NativeBridge.nativeRetrieve(
                        handle,
                        "[\"${RECORDS[0].pointId}\"]",
                    )
                    val high = NativeBridge.nativeScroll(handle, severityHighFilter(), 10, null)
                    val combined = NativeBridge.nativeScroll(handle, combinedUniqueFilter(), 10, null)
                    val search = NativeBridge.nativeSearchWithFilter(
                        handle,
                        floatArrayOf(0.9f, 0.1f, 0.1f, 0.0f),
                        4,
                        null,
                    )
                    NativeBridge.nativeClose(handle)
                    val retrieveOk = retrieved.contains("\"id\":\"MR-001\"") &&
                        retrieved.contains("\"severity\":\"high\"") &&
                        retrieved.contains("\"machine_id\":\"M-042\"")
                    val highOk = high.contains("\"id\":\"MR-001\"") &&
                        high.contains("\"id\":\"MR-003\"") &&
                        !high.contains("\"id\":\"MR-002\"") &&
                        !high.contains("\"id\":\"MR-004\"")
                    val combinedOk = combined.contains("\"id\":\"MR-001\"") &&
                        !combined.contains("\"id\":\"MR-003\"")
                    val searchTop = search.contains("\"id\":\"${RECORDS[0].pointId}\"")
                    println(
                        "$POSITIVE_VERIF count=$count retrieve_ok=$retrieveOk " +
                            "high_ok=$highOk combined_ok=$combinedOk search_top=$searchTop",
                    )
                }

                else -> throw IllegalArgumentException("unknown phase: $phase")
            }
        }

        private const val POSITIVE_VERIF = "VERIFIED"
    }

    private fun newTempDir(prefix: String): File {
        val dir = File.createTempFile(prefix, "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    private fun writeFixture(store: SpikeStore) {
        for ((field, schema) in INDEX_FIELDS) store.createIndex(field, schema)
        for (record in RECORDS) store.upsert(record.pointId, record.vector, payload(record))
        store.flush()
    }

    private fun assertFullPayload(record: JSONObject, expected: SpikeRecord, version: Int, updatedAt: Long) {
        assertEquals("record_type", "maintenance_record", record.getString("record_type"))
        assertEquals("id", expected.recordId, record.getString("id"))
        assertEquals("version", version, record.getInt("version"))
        assertEquals("created_at", CREATED_AT, record.getLong("created_at"))
        assertEquals("updated_at", updatedAt, record.getLong("updated_at"))
        assertEquals("machine_id", expected.machineId, record.getString("machine_id"))
        assertEquals("severity", expected.severity, record.getString("severity"))
        assertEquals("status", expected.status, record.getString("status"))
        assertEquals("plant", expected.plant, record.getString("plant"))
        assertEquals("line", expected.line, record.getString("line"))
        assertEquals("zone", "PRESS-04", record.getString("zone"))
    }

    private fun payloadOf(result: JSONObject): JSONObject = result.getJSONObject("payload")

    private fun recordIds(payloads: List<JSONObject>): List<String> =
        payloads.map { it.getString("id") }.sorted()

    private fun runChild(lib: String, phase: String, dir: File): ChildResult {
        val java = "${System.getProperty("java.home")}/bin/java"
        val classpath = System.getProperty("java.class.path")
        val process = ProcessBuilder(
            java,
            "-D$LIB_PROPERTY=$lib",
            "-D$PHASE_PROPERTY=$phase",
            "-D$PERSIST_DIR_PROPERTY=${dir.absolutePath}",
            "-cp",
            classpath,
            QdrantRecordPersistenceTest::class.java.name,
            phase,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        process.destroyForcibly()
        return if (finished && process.exitValue() == 0) {
            ChildResult(true, output)
        } else {
            ChildResult(false, output)
        }
    }

    private data class ChildResult(val isSuccess: Boolean, val output: String)

    // ------------------------------------------------------------------
    // Task 4 — persistence across restart (same process, graceful close)
    // ------------------------------------------------------------------

    @Test
    fun payloadAndVectorSurviveReopenInSameProcess() {
        val dir = newTempDir("edgememo-phase10-reopen")
        val store = SpikeStore(dir)
        try {
            store.create(DIMENSION)
            writeFixture(store)

            assertEquals(4L, store.count(null, exact = true))

            // Payload verify before restart.
            val before = store.retrieve(RECORDS[0].pointId)
            assertEquals(1, before.size)
            assertFullPayload(payloadOf(before[0]), RECORDS[0], version = 1, updatedAt = CREATED_AT)

            // Vector verify before restart: query close to MR-001's vector.
            val hits = store.search(floatArrayOf(0.9f, 0.1f, 0.1f, 0.0f), 4)
            assertEquals(RECORDS[0].pointId, hits.first().getString("id"))
            assertTrue(hits.first().getDouble("score") > 0.9)
            assertFullPayload(hits.first().getJSONObject("payload"), RECORDS[0], 1, CREATED_AT)

            store.close()

            val reopened = SpikeStore(dir)
            reopened.open()

            // Payload verify after restart.
            val after = reopened.retrieve(RECORDS[0].pointId)
            assertEquals(1, after.size)
            assertFullPayload(payloadOf(after[0]), RECORDS[0], version = 1, updatedAt = CREATED_AT)

            // Vector verify after restart.
            val hitsAfter = reopened.search(floatArrayOf(0.9f, 0.1f, 0.1f, 0.0f), 4)
            assertEquals(RECORDS[0].pointId, hitsAfter.first().getString("id"))
            assertTrue(hitsAfter.first().getDouble("score") > 0.9)

            // Payload filter after restart (no index recreation here).
            val high = reopened.scroll(severityHighFilter(), 10)
            assertEquals(listOf("MR-001", "MR-003"), recordIds(high.map(::payloadOf)))

            assertEquals(4L, reopened.count(null, exact = true))
            reopened.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // Task 7 — crash-style persistence: write process exits without close
    // ------------------------------------------------------------------

    @Test
    fun payloadRecordSurvivesProcessDeathWithoutClose() {
        val dir = newTempDir("edgememo-phase10-crash")
        try {
            val lib = TestNativeLoader.nativeLibraryPath()
            val crashWrite = runChild(lib, "phase10crashwrite", dir)
            assertTrue("crash-write process must succeed:\n${crashWrite.output}", crashWrite.isSuccess)

            // A DIFFERENT process (this test JVM) opens the same path and
            // re-reads everything from disk.
            val store = SpikeStore(dir)
            store.open()
            assertEquals(4L, store.count(null, exact = true))

            val record = store.retrieve(RECORDS[0].pointId)
            assertEquals(1, record.size)
            assertFullPayload(payloadOf(record[0]), RECORDS[0], version = 1, updatedAt = CREATED_AT)

            val high = store.scroll(severityHighFilter(), 10)
            assertEquals(listOf("MR-001", "MR-003"), recordIds(high.map(::payloadOf)))

            val hits = store.search(floatArrayOf(0.9f, 0.1f, 0.1f, 0.0f), 4)
            assertEquals(RECORDS[0].pointId, hits.first().getString("id"))
            store.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun secondSeparateProcessReadsRecordsAndFilters() {
        val dir = newTempDir("edgememo-phase10-two-process")
        try {
            val lib = TestNativeLoader.nativeLibraryPath()
            val crashWrite = runChild(lib, "phase10crashwrite", dir)
            assertTrue("crash-write process must succeed:\n${crashWrite.output}", crashWrite.isSuccess)

            val verify = runChild(lib, "phase10verify", dir)
            assertTrue("verify process must succeed:\n${verify.output}", verify.isSuccess)
            assertTrue(
                "verify process must find payloads, vectors and filters, got:\n${verify.output}",
                verify.output.contains(
                    "VERIFIED count=4 retrieve_ok=true high_ok=true combined_ok=true search_top=true",
                ),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // Task 3 + 11 — filter correctness over the full record set
    // ------------------------------------------------------------------

    @Test
    fun payloadFiltersReturnExactExpectedSets() {
        val dir = newTempDir("edgememo-phase10-filters")
        val store = SpikeStore(dir)
        try {
            store.create(DIMENSION)
            writeFixture(store)

            assertEquals(listOf("MR-001", "MR-003"), recordIds(store.scroll(severityHighFilter(), 10).map(::payloadOf)))
            assertEquals(listOf("MR-002", "MR-003"), recordIds(store.scroll(statusResolvedFilter(), 10).map(::payloadOf)))
            assertEquals(listOf("MR-001", "MR-002"), recordIds(store.scroll(machine042Filter(), 10).map(::payloadOf)))
            // Task-3 combined filter: record_type + severity + line.
            assertEquals(listOf("MR-001", "MR-003"), recordIds(store.scroll(combinedFilter(), 10).map(::payloadOf)))
            // Adding machine_id narrows it to exactly one record.
            assertEquals(listOf("MR-001"), recordIds(store.scroll(combinedUniqueFilter(), 10).map(::payloadOf)))

            assertEquals(2L, store.count(severityHighFilter(), exact = true))
            assertEquals(4L, store.count(null, exact = true))

            // Filtered search: dense query scoped to severity=critical must
            // return MR-004's point and payload.
            val hits = store.search(floatArrayOf(0.1f, 0.1f, 0.1f, 0.9f), 4, severityCriticalFilter())
            assertEquals(listOf("MR-004"), recordIds(hits.map(::payloadOf)))
            store.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // Task 5 — update persistence
    // ------------------------------------------------------------------

    @Test
    fun updatePersistsAcrossRestartAndStaleVersionIsGone() {
        val dir = newTempDir("edgememo-phase10-update")
        try {
            val store = SpikeStore(dir)
            store.create(DIMENSION)
            store.createIndex("severity", "keyword")
            store.upsert(RECORDS[0].pointId, RECORDS[0].vector, payload(RECORDS[0], version = 1))
            store.flush()
            store.close()

            // Restart, then update the same point id in place.
            val updater = SpikeStore(dir)
            updater.open()
            val v2 = payload(RECORDS[0], version = 2, updatedAt = UPDATED_AT_V2).apply {
                put("severity", "critical")
                put("status", "open")
            }
            updater.upsert(RECORDS[0].pointId, RECORDS[0].vector, v2)
            updater.flush()
            updater.close()

            // Restart again and verify the new state.
            val verifier = SpikeStore(dir)
            verifier.open()
            val after = verifier.retrieve(RECORDS[0].pointId)
            assertEquals(1, after.size)
            val v2Payload = payloadOf(after[0])
            assertEquals("critical", v2Payload.getString("severity"))
            assertEquals("open", v2Payload.getString("status"))
            assertEquals(2, v2Payload.getInt("version"))
            assertEquals(UPDATED_AT_V2, v2Payload.getLong("updated_at"))
            assertEquals("M-042", v2Payload.getString("machine_id"))
            assertEquals("LINE-A", v2Payload.getString("line"))

            // Stale version-1 state must not be observable anywhere.
            assertTrue(verifier.scroll(versionOneFilter(), 10).isEmpty())
            assertTrue(verifier.scroll(severityHighFilter(), 10).isEmpty())
            assertEquals(listOf("MR-001"), recordIds(verifier.scroll(severityCriticalFilter(), 10).map(::payloadOf)))
            assertEquals(1L, verifier.count(null, exact = true))
            verifier.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // Task 6 — delete persistence
    // ------------------------------------------------------------------

    @Test
    fun deletePersistsAcrossRestart() {
        val dir = newTempDir("edgememo-phase10-delete")
        try {
            val store = SpikeStore(dir)
            store.create(DIMENSION)
            store.upsert(RECORDS[0].pointId, RECORDS[0].vector, payload(RECORDS[0]))
            store.flush()
            store.close()

            // Restart, delete, restart.
            val deleter = SpikeStore(dir)
            deleter.open()
            deleter.delete(RECORDS[0].pointId)
            deleter.close()

            val verifier = SpikeStore(dir)
            verifier.open()
            assertTrue(verifier.retrieve(RECORDS[0].pointId).isEmpty())
            assertEquals(0L, verifier.count(null, exact = true))
            assertTrue(verifier.scroll(null, 10).isEmpty())
            verifier.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // Task 9 — payload index survives restart (no index recreation)
    // ------------------------------------------------------------------

    @Test
    fun payloadIndexSurvivesRestartWithoutRecreation() {
        val dir = newTempDir("edgememo-phase10-index")
        try {
            val store = SpikeStore(dir)
            store.create(DIMENSION)
            writeFixture(store)
            store.close()

            // Reopen and filter WITHOUT calling createIndex again. The
            // behavioral proof: filtered queries return exact sets from the
            // persisted shard.
            val reopened = SpikeStore(dir)
            reopened.open()
            assertEquals(listOf("MR-001", "MR-003"), recordIds(reopened.scroll(severityHighFilter(), 10).map(::payloadOf)))
            assertEquals(listOf("MR-001"), recordIds(reopened.scroll(combinedUniqueFilter(), 10).map(::payloadOf)))
            reopened.close()

            // On-disk proof that the collection metadata and the payload index
            // schema persisted (observed shard layout from qdrant-edge 0.8.0):
            //   <shard>/edge_config.json                    collection config
            //   <shard>/segments/<uuid>/payload_index/config.json
            //                                               index schema + per-field index dirs
            assertTrue("edge_config.json must persist", File(dir, "edge_config.json").isFile)
            val segments = File(dir, "segments").listFiles().orEmpty()
            assertTrue("a segment must exist", segments.isNotEmpty())
            val indexSchemaFiles = segments.flatMap { segment ->
                File(segment, "payload_index/config.json").let {
                    if (it.isFile) listOf(it) else emptyList()
                }
            }
            assertTrue(
                "per-segment payload index schema must persist after restart; " +
                    "segments=${segments.map { it.name }}",
                indexSchemaFiles.isNotEmpty(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // Task 14 — baseline performance measurements (host JVM only)
    // ------------------------------------------------------------------

    @Test
    fun baselineTimingsAreCollected() {
        val dir = newTempDir("edgememo-phase10-timings")
        val store = SpikeStore(dir)
        try {
            val createNanos = timedNanos {
                store.create(DIMENSION)
            }

            var upsertNanos = 0L
            val upsertCount = RECORDS.size
            for (record in RECORDS) {
                upsertNanos += timedNanos {
                    store.upsert(record.pointId, record.vector, payload(record))
                }
            }

            val flushNanos = timedNanos { store.flush() }

            val retrieveNanos = timedNanos {
                store.retrieve(RECORDS[0].pointId)
            }

            val filterNanos = timedNanos {
                store.scroll(severityHighFilter(), 10)
            }

            val searchNanos = timedNanos {
                store.search(floatArrayOf(0.9f, 0.1f, 0.1f, 0.0f), 4)
            }

            store.close()

            val reopenNanos = timedNanos {
                store.open()
            }
            store.close()

            val payloadBytes = payload(RECORDS[0]).toString().toByteArray(Charsets.UTF_8).size

            println(
                "PHASE10_TIMING create_ms=${createNanos / 1_000_000.0} " +
                    "upsert_avg_ms=${(upsertNanos / upsertCount) / 1_000_000.0} " +
                    "flush_ms=${flushNanos / 1_000_000.0} " +
                    "retrieve_ms=${retrieveNanos / 1_000_000.0} " +
                    "filter_ms=${filterNanos / 1_000_000.0} " +
                    "search_ms=${searchNanos / 1_000_000.0} " +
                    "reopen_ms=${reopenNanos / 1_000_000.0} " +
                    "payload_bytes=$payloadBytes records=${RECORDS.size}",
            )

        } finally {
            dir.deleteRecursively()
        }
    }

    private fun timedNanos(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }
}
