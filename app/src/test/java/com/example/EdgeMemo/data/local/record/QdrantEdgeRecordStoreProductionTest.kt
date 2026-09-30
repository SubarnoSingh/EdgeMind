package com.example.EdgeMemo.data.local.record

import com.example.EdgeMemo.core.record.JsonValue
import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordFilter
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordQuery
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.record.SyncDecision
import com.example.EdgeMemo.core.record.getString
import com.example.EdgeMemo.native.qdrant.NativeBridge
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
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
 * Phase 12B.4: verifies the REAL Android production path
 *
 *   QdrantEdgeRecordStore → NativeBridge (JNI) → Rust → qdrant-edge 0.8.0
 *
 * for payload-only records using qdrant-edge's genuine representation
 * (absent named vector — NOT a fake zero-dimensional vector):
 *
 *  - vector-bearing + payload-only records in one mixed collection
 *  - upsert / retrieve / scroll / count / filtered search / update / delete
 *  - payload indexes on payload-only points
 *  - persistence across close/reopen
 *  - persistence across process death without graceful close
 *
 * Every assertion re-reads from the shard. Nothing is mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QdrantEdgeRecordStoreProductionTest {

    companion object {
        private const val DIMENSION = 4
        private const val LIB_PROPERTY = "edgememo.qdrant.lib"
        private const val PHASE_PROPERTY = "edge.ph"
        private const val PERSIST_DIR_PROPERTY = "edge.persist.dir"

        private const val VECTOR_ID = "00000000-0000-0000-0000-0000000000a1"
        private const val PAYLOAD_ID_1 = "00000000-0000-0000-0000-0000000000b1"
        private const val PAYLOAD_ID_2 = "00000000-0000-0000-0000-0000000000b2"

        private fun payloadOnlyEnvelope(state: String, opId: String, version: Int = 1): String =
            "{" +
                "\"_record_type\":\"outbox_op\"," +
                "\"operation_id\":\"$opId\"," +
                "\"_record_id\":\"123e4567-e89b-12d3-a456-426614174000\"," +
                "\"_state\":\"$state\"," +
                "\"_attempts\":0," +
                "\"_created_at\":1700000000000," +
                "\"_updated_at\":1700000000000," +
                "\"_version\":$version," +
                "\"_sync_decision\":\"SYNC\"," +
                "\"_redacted\":false," +
                "\"payload_title\":\"Pump seal procedure\"" +
                "}"

        /** Plain-JVM child entry: writes mixed records and exits WITHOUT close. */
        @JvmStatic
        fun main(args: Array<String>) {
            val phase = args.firstOrNull() ?: System.getProperty(PHASE_PROPERTY)
                ?: throw IllegalArgumentException("phase required")
            val persistDir = File(
                System.getProperty(PERSIST_DIR_PROPERTY)
                    ?: throw IllegalArgumentException("$PERSIST_DIR_PROPERTY required"),
            )
            System.load(System.getProperty(LIB_PROPERTY))
            when (phase) {
                "payloadOnlyCrashWrite" -> {
                    persistDir.mkdirs()
                    val handle = NativeBridge.nativeCreate(persistDir.absolutePath, DIMENSION)
                    NativeBridge.nativeCreatePayloadIndex(handle, "_record_type", "keyword")
                    NativeBridge.nativeCreatePayloadIndex(handle, "_state", "keyword")
                    NativeBridge.nativeCreatePayloadIndex(handle, "operation_id", "keyword")
                    NativeBridge.nativeCreatePayloadIndex(handle, "_version", "integer")
                    // One genuine vector record for the mixed-collection proof.
                    NativeBridge.nativeUpsertWithPayload(
                        handle,
                        VECTOR_ID,
                        floatArrayOf(1f, 0f, 0f, 0f),
                        """{"_record_type":"procedure","_state":"N/A","operation_id":"none","_version":1}""",
                    )
                    NativeBridge.nativeUpsertPayloadOnly(
                        handle,
                        PAYLOAD_ID_1,
                        payloadOnlyEnvelope("PENDING", "UPSERT:123e4567-e89b-12d3-a456-426614174000:1"),
                    )
                    NativeBridge.nativeUpsertPayloadOnly(
                        handle,
                        PAYLOAD_ID_2,
                        payloadOnlyEnvelope("ACKED", "UPSERT:123e4567-e89b-12d3-a456-426614174000:2", version = 2),
                    )
                    NativeBridge.nativeFlush(handle)
                    // No nativeClose: JVM exit emulates Android process death.
                }
                else -> throw IllegalArgumentException("unknown phase: $phase")
            }
        }

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    private fun tempDir(): File {
        val dir = File.createTempFile("edgememo-12b4-", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    private fun knowledgeRecord(
        uuid: String,
        vector: FloatArray?,
        severity: String,
        version: Int = 1,
    ): Record = Record(
        id = RecordId.fromString(uuid),
        recordType = RecordType.PROCEDURE,
        entityId = "P-$uuid",
        vector = vector,
        payload = mapOf(
            "title" to JsonValue.fromString("Replace pump seal"),
            "severity" to JsonValue.fromString(severity),
        ),
        version = version,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
        syncDecision = SyncDecision.SYNC,
    )

    private fun payloadOnlyRecord(uuid: String, opId: String, state: String = "PENDING"): Record =
        Record(
            id = RecordId.fromString(uuid),
            recordType = RecordType.OUTBOX_OP,
            entityId = null,
            vector = null,
            payload = mapOf(
                "operation_id" to JsonValue.fromString(opId),
                "_state" to JsonValue.fromString(state),
                "_attempts" to JsonValue.fromInt(0),
                "_record_id" to JsonValue.fromString("123e4567-e89b-12d3-a456-426614174000"),
                "payload_title" to JsonValue.fromString("Gate code 4821"),
            ),
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            syncDecision = SyncDecision.SYNC,
        )

    @Test
    fun mixedVectorAndPayloadOnlyRecordsThroughProductionPath() = runBlocking {
        val dir = tempDir()
        val store = QdrantEdgeRecordStore(dir)
        try {
            store.initialize(DIMENSION)
            store.ensureIndexes()

            val vector = knowledgeRecord(VECTOR_ID, floatArrayOf(1f, 0f, 0f, 0f), "high")
            val payloadOnly1 = payloadOnlyRecord(PAYLOAD_ID_1, "UPSERT:123e4567-e89b-12d3-a456-426614174000:1")
            val payloadOnly2 = payloadOnlyRecord(PAYLOAD_ID_2, "UPSERT:123e4567-e89b-12d3-a456-426614174000:2")
            store.upsert(vector)
            store.upsert(payloadOnly1)
            store.upsert(payloadOnly2)

            // Retrieve: production path distinguishes genuine absence from zero-length vector.
            val retrievedVector = store.get(RecordId.fromString(VECTOR_ID))!!
            assertNotNull(retrievedVector.vector)
            assertEquals(DIMENSION, retrievedVector.vector!!.size)
            assertEquals("high", retrievedVector.payload["severity"]?.getString())

            val retrievedPayloadOnly = store.get(RecordId.fromString(PAYLOAD_ID_1))!!
            assertNull(
                "payload-only point must round-trip with NO vector, not a zero-length one",
                retrievedPayloadOnly.vector,
            )
            assertEquals("Gate code 4821", retrievedPayloadOnly.payload["payload_title"]?.getString())

            // Batch with mixed vector/null in one call.
            store.upsertBatch(
                listOf(
                    knowledgeRecord("00000000-0000-0000-0000-0000000000c9", floatArrayOf(0f, 1f, 0f, 0f), "low"),
                    payloadOnlyRecord("00000000-0000-0000-0000-0000000000d0", "UPSERT:123e4567-e89b-12d3-a456-426614174000:9"),
                ),
            )
            assertNull(store.get(RecordId.fromString("00000000-0000-0000-0000-0000000000d0"))!!.vector)
            assertNotNull(store.get(RecordId.fromString("00000000-0000-0000-0000-0000000000c9"))!!.vector)

            // getMany across both kinds.
            val many = store.getMany(
                listOf(
                    RecordId.fromString(VECTOR_ID),
                    RecordId.fromString(PAYLOAD_ID_1),
                ),
            )
            assertEquals(2, many.size)

            // Count: whole collection and per record type (record-type OR filter).
            assertEquals(5L, store.count(RecordQuery.Count()))
            assertEquals(
                3L,
                store.count(RecordQuery.Count(recordTypes = setOf(RecordType.OUTBOX_OP))),
            )
            assertEquals(
                2L,
                store.count(RecordQuery.Count(recordTypes = setOf(RecordType.PROCEDURE))),
            )

            // Scroll: filtered by state on payload-only points, using compiled In filter.
            val pending = store.scroll(
                RecordQuery.Scroll(
                    filter = RecordFilter.match("_state", "PENDING"),
                    limit = 10,
                    recordTypes = setOf(RecordType.OUTBOX_OP),
                ),
            )
            assertEquals(3, pending.records.size)

            // Update a payload-only record: replace in place, stale state gone.
            store.upsert(payloadOnlyRecord(PAYLOAD_ID_1, "UPSERT:123e4567-e89b-12d3-a456-426614174000:1", state = "FAILED"))
            val updated = store.get(RecordId.fromString(PAYLOAD_ID_1))!!
            assertEquals("FAILED", updated.payload["_state"]?.getString())
            assertEquals(
                2L,
                store.count(
                    RecordQuery.Count(
                        filter = RecordFilter.match("_state", "PENDING"),
                        recordTypes = setOf(RecordType.OUTBOX_OP),
                    ),
                ),
            )

            // Soft delete keeps the payload-only point retrievable but tombstoned.
            val softDeleted = store.softDelete(RecordId.fromString(PAYLOAD_ID_2))
            assertTrue(softDeleted.tombstone)
            assertTrue(store.get(RecordId.fromString(PAYLOAD_ID_2))!!.tombstone)

            // Physical delete.
            assertTrue(store.delete(RecordId.fromString(PAYLOAD_ID_2)))
            assertNull(store.get(RecordId.fromString(PAYLOAD_ID_2)))

            // Search: payload-only points must never appear in vector search.
            val hits = store.search(
                RecordQuery.Search(vector = floatArrayOf(1f, 0f, 0f, 0f), limit = 10),
            )
            val hitIds = hits.map { it.record.id.uuid }.toSet()
            assertTrue(hitIds.contains(VECTOR_ID))
            assertFalse(hitIds.contains(PAYLOAD_ID_1))
            assertFalse(hitIds.contains(PAYLOAD_ID_2))
        } finally {
            runCatching { store.close() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun payloadOnlyRecordsSurviveReopenWithoutIndexRecreation() = runBlocking {
        val dir = tempDir()
        val writer = QdrantEdgeRecordStore(dir)
        try {
            writer.initialize(DIMENSION)
            writer.ensureIndexes()
            writer.upsert(payloadOnlyRecord(PAYLOAD_ID_1, "UPSERT:123e4567-e89b-12d3-a456-426614174000:1"))
            writer.upsert(knowledgeRecord(VECTOR_ID, floatArrayOf(1f, 0f, 0f, 0f), "high"))
            writer.close()

            // Reopen: NO ensureIndexes call — persisted indexes must still filter.
            val reader = QdrantEdgeRecordStore(dir)
            reader.open()
            val payloadOnly = reader.get(RecordId.fromString(PAYLOAD_ID_1))
            assertNotNull(payloadOnly)
            assertNull(payloadOnly!!.vector)
            val opFiltered = reader.scroll(
                RecordQuery.Scroll(
                    filter = RecordFilter.match("operation_id", "UPSERT:123e4567-e89b-12d3-a456-426614174000:1"),
                    limit = 10,
                ),
            )
            assertEquals(listOf(PAYLOAD_ID_1), opFiltered.records.map { it.id.uuid })
            assertEquals(2L, reader.count(RecordQuery.Count()))
            reader.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun payloadOnlyRecordsSurviveProcessDeathWithoutClose() {
        val dir = tempDir()
        try {
            val lib = TestNativeLoader.nativeLibraryPath()
            val java = "${System.getProperty("java.home")}/bin/java"
            val process = ProcessBuilder(
                java,
                "-D$LIB_PROPERTY=$lib",
                "-D$PHASE_PROPERTY=payloadOnlyCrashWrite",
                "-D$PERSIST_DIR_PROPERTY=${dir.absolutePath}",
                "-cp",
                System.getProperty("java.class.path"),
                QdrantEdgeRecordStoreProductionTest::class.java.name,
                "payloadOnlyCrashWrite",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().decodeToString()
            val finished = process.waitFor(120, TimeUnit.SECONDS)
            process.destroyForcibly()
            assertTrue(
                "crash-write process must succeed:\n$output",
                finished && process.exitValue() == 0,
            )

            // This JVM (a different process) opens the shard through the
            // production store and re-reads everything from disk.
            runBlocking {
                val store = QdrantEdgeRecordStore(dir)
                store.open()
                assertEquals(3L, store.count(RecordQuery.Count()))

                val pending = store.get(RecordId.fromString(PAYLOAD_ID_1))!!
                assertNull(pending.vector)
                assertEquals("PENDING", pending.payload["_state"]?.getString())
                assertEquals(
                    "UPSERT:123e4567-e89b-12d3-a456-426614174000:1",
                    pending.payload["operation_id"]?.getString(),
                )

                val acked = store.get(RecordId.fromString(PAYLOAD_ID_2))!!
                assertEquals("ACKED", acked.payload["_state"]?.getString())
                assertEquals(2, acked.version)

                val vectored = store.get(RecordId.fromString(VECTOR_ID))!!
                assertNotNull(vectored.vector)

                // Persisted indexes: filtered scroll without recreation.
                val filtered = store.scroll(
                    RecordQuery.Scroll(
                        filter = RecordFilter.match("_state", "ACKED"),
                        limit = 10,
                    ),
                )
                assertEquals(listOf(PAYLOAD_ID_2), filtered.records.map { it.id.uuid })
                store.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
