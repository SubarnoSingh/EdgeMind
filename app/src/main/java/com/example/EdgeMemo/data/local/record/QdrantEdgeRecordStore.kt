package com.example.EdgeMemo.data.local.record

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.record.*
import com.example.EdgeMemo.native.qdrant.NativeBridge
import com.example.EdgeMemo.native.qdrant.QdrantNativeException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Production implementation of [LocalRecordStore] backed by qdrant-edge
 * through the JNI boundary.
 */
class QdrantEdgeRecordStore(
    private val directory: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalRecordStore {

    private val mutex = Mutex()
    private var handle: Long = 0L

    override val isOpen: Boolean
        get() = handle != 0L

    override suspend fun initialize(dimension: Int) {
        mutex.withLock {
            withContext(dispatcher) {
                NativeLoader.ensure()
                require(handle == 0L) { "Record store already initialized" }
                directory.mkdirs()
                handle = NativeBridge.nativeCreate(directory.absolutePath, dimension)
            }
        }
    }

    override suspend fun open() {
        mutex.withLock {
            withContext(dispatcher) {
                NativeLoader.ensure()
                require(handle == 0L) { "Record store already open" }
                handle = NativeBridge.nativeOpen(directory.absolutePath)
            }
        }
    }

    override suspend fun ensureReady(dimension: Int) {
        mutex.withLock {
            withContext(dispatcher) {
                NativeLoader.ensure()
                if (handle == 0L) {
                    directory.mkdirs()
                    if (isShardPresent(directory)) {
                        handle = NativeBridge.nativeOpen(directory.absolutePath)
                    } else {
                        handle = NativeBridge.nativeCreate(directory.absolutePath, dimension)
                    }
                }
            }
        }
    }

    override suspend fun ensureIndexes() {
        mutex.withLock {
            withContext(dispatcher) {
                val current = requireHandle()
                // Core envelope indexes
                createIndexIfNeeded(current, "_record_type", "keyword")
                createIndexIfNeeded(current, "_entity_id", "keyword")
                createIndexIfNeeded(current, "_version", "integer")
                // Timestamp envelope fields store epoch-MILLIS integers.
                // VERIFIED against qdrant-edge 0.8.0: a `datetime` index only
                // range-filters RFC3339 string payloads (numeric millis payloads
                // match nothing through range conditions), so the numeric
                // envelope timestamps are indexed as `integer` to keep
                // DateRange/lease filters exact on real data.
                createIndexIfNeeded(current, "_created_at", "integer")
                createIndexIfNeeded(current, "_updated_at", "integer")
                createIndexIfNeeded(current, "_source", "keyword")
                createIndexIfNeeded(current, "_sync_decision", "keyword")
                createIndexIfNeeded(current, "_sync_state", "keyword")
                createIndexIfNeeded(current, "_subject_key", "keyword")
                createIndexIfNeeded(current, "_content_hash", "keyword")
                createIndexIfNeeded(current, "_supersedes", "keyword")
                createIndexIfNeeded(current, "_tombstone", "bool")
                createIndexIfNeeded(current, "_deleted_at", "integer")
                createIndexIfNeeded(current, "_origin", "keyword")
                createIndexIfNeeded(current, "_authority", "keyword")
                // Phase 12 operation-record indexes. These are additive to the
                // generic record envelope and are required for bounded,
                // indexed idempotency/retry queries.
                createIndexIfNeeded(current, "operation_id", "keyword")
                createIndexIfNeeded(current, "_record_id", "keyword")
                createIndexIfNeeded(current, "_state", "keyword")
                createIndexIfNeeded(current, "_lease_until", "integer")
                createIndexIfNeeded(current, "_last_synced_version", "integer")
                createIndexIfNeeded(current, "_tags", "keyword")
                // Common domain indexes (extendable by callers)
                createIndexIfNeeded(current, "machine_id", "keyword")
                createIndexIfNeeded(current, "machine_type", "keyword")
                createIndexIfNeeded(current, "plant", "keyword")
                createIndexIfNeeded(current, "line", "keyword")
                createIndexIfNeeded(current, "zone", "keyword")
                createIndexIfNeeded(current, "failure_type", "keyword")
                createIndexIfNeeded(current, "severity", "keyword")
                createIndexIfNeeded(current, "status", "keyword")
                createIndexIfNeeded(current, "work_type", "keyword")
                createIndexIfNeeded(current, "part_id", "keyword")
                createIndexIfNeeded(current, "technician_id", "keyword")
                createIndexIfNeeded(current, "document_id", "keyword")
                createIndexIfNeeded(current, "procedure_id", "keyword")
                createIndexIfNeeded(current, "inspection_type", "keyword")
                createIndexIfNeeded(current, "geo", "geo")
            }
        }
    }

    private fun createIndexIfNeeded(handle: Long, field: String, schema: String) {
        try {
            NativeBridge.nativeCreatePayloadIndex(handle, field, schema)
        } catch (e: QdrantNativeException) {
            // Index may already exist; ignore "already exists" errors
            val msg = e.message?.lowercase() ?: ""
            if (!msg.contains("already exists")) {
                throw e
            }
        }
    }

    override suspend fun close() {
        mutex.withLock {
            withContext(dispatcher) {
                if (handle != 0L) {
                    NativeBridge.nativeClose(handle)
                    handle = 0L
                }
            }
        }
    }

    override suspend fun upsert(record: Record): Record {
        mutex.withLock {
            return withContext(dispatcher) {
                val current = requireHandle()
                val payloadJson = record.toFullPayload().toJsonString()
                if (record.vector == null) {
                    NativeBridge.nativeUpsertPayloadOnly(current, record.id.uuid, payloadJson)
                } else {
                    NativeBridge.nativeUpsertWithPayload(current, record.id.uuid, record.vector, payloadJson)
                }
                NativeBridge.nativeFlush(current)
                record
            }
        }
    }

    override suspend fun upsertBatch(records: List<Record>): List<Record> {
        if (records.isEmpty()) return emptyList()
        mutex.withLock {
            return withContext(dispatcher) {
                val current = requireHandle()
                val jsonArray = JSONArray()
                for (record in records) {
                    val obj = JSONObject()
                    obj.put("id", record.id.uuid)
                    val vector = record.vector
                    obj.put("vector", vector?.let { JSONArray(it) } ?: JSONObject.NULL)
                    obj.put("payload", record.toFullPayload().toJsonObject())
                    jsonArray.put(obj)
                }
                NativeBridge.nativeUpsertBatchWithPayload(current, jsonArray.toString())
                NativeBridge.nativeFlush(current)
                records
            }
        }
    }

    override suspend fun delete(id: RecordId): Boolean {
        mutex.withLock {
            return withContext(dispatcher) {
                val current = requireHandle()
                val existed = retrieveSync(current, id) != null
                if (existed) {
                    NativeBridge.nativeDelete(current, id.uuid)
                    NativeBridge.nativeFlush(current)
                }
                existed
            }
        }
    }

    override suspend fun softDelete(id: RecordId): Record {
        mutex.withLock {
            return withContext(dispatcher) {
                val current = requireHandle()
                val record = retrieveSync(current, id)
                    ?: throw EdgeError.MemoryNotFound(id.uuid)
                val updated = record.copy(
                    tombstone = true,
                    deletedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    version = record.version + 1,
                    // A sync-eligible record whose tombstone is newer than its
                    // acknowledged state HAS pending content changes, even when
                    // its previous version was already SYNCED. Writing PENDING
                    // here keeps the unpropagated tombstone visible to the R4
                    // reconciliation scan if the process dies before change
                    // detection (§18 case 9). LOCAL_ONLY deletions stay local.
                    syncState = if (
                        record.syncDecision != SyncDecision.LOCAL_ONLY &&
                        record.syncState == SyncState.SYNCED
                    ) {
                        SyncState.PENDING
                    } else {
                        record.syncState
                    },
                )
                val payloadJson = updated.toFullPayload().toJsonString()
                if (updated.vector == null) {
                    NativeBridge.nativeUpsertPayloadOnly(current, id.uuid, payloadJson)
                } else {
                    NativeBridge.nativeUpsertWithPayload(current, id.uuid, updated.vector, payloadJson)
                }
                NativeBridge.nativeFlush(current)
                updated
            }
        }
    }

    override suspend fun get(id: RecordId): Record? {
        mutex.withLock {
            return withContext(dispatcher) {
                retrieveSync(requireHandle(), id)
            }
        }
    }

    override suspend fun getMany(ids: List<RecordId>): List<Record> {
        if (ids.isEmpty()) return emptyList()
        mutex.withLock {
            return withContext(dispatcher) {
                val current = requireHandle()
                val idsJson = JSONArray(ids.map { it.uuid }).toString()
                val json = NativeBridge.nativeRetrieve(current, idsJson)
                parseRecords(json)
            }
        }
    }

    override suspend fun exists(id: RecordId): Boolean {
        mutex.withLock {
            return withContext(dispatcher) {
                retrieveSync(requireHandle(), id) != null
            }
        }
    }

    override suspend fun query(query: RecordQuery): QueryResult = when (query) {
        is RecordQuery.ByIds -> {
            val records = getMany(query.ids)
            QueryResult.Records(records)
        }
        is RecordQuery.Scroll -> {
            val page = scroll(query)
            QueryResult.Page(page)
        }
        is RecordQuery.Search -> {
            val records = search(query)
            QueryResult.ScoredRecords(records)
        }
        is RecordQuery.Count -> {
            val count = count(query)
            QueryResult.Count(count)
        }
    }

    override suspend fun scroll(query: RecordQuery.Scroll): RecordPage {
        mutex.withLock {
            return withContext(dispatcher) {
                val current = requireHandle()
                var filter = query.filter
                if (query.recordTypes != null && query.recordTypes!!.isNotEmpty()) {
                    val typeFilter = RecordFilter.or(*query.recordTypes!!.map { RecordFilter.recordType(it) }.toTypedArray())
                    filter = if (filter != null) RecordFilter.and(filter, typeFilter) else typeFilter
                }
                val filterJson = FilterCompiler.compile(filter)
                val json = NativeBridge.nativeScroll(current, filterJson, query.limit, query.offsetId)
                val records = parseRecords(json)
                val nextOffset = if (records.size == query.limit) records.last().id.uuid else null
                RecordPage(records, nextOffset)
            }
        }
    }

    override suspend fun search(query: RecordQuery.Search): List<ScoredRecord> {
        mutex.withLock {
            return withContext(dispatcher) {
                val current = requireHandle()
                var filter = query.filter
                if (query.recordTypes != null && query.recordTypes!!.isNotEmpty()) {
                    val typeFilter = RecordFilter.or(*query.recordTypes!!.map { RecordFilter.recordType(it) }.toTypedArray())
                    filter = if (filter != null) RecordFilter.and(filter, typeFilter) else typeFilter
                }
                val filterJson = FilterCompiler.compile(filter)
                val json = NativeBridge.nativeSearchWithFilter(current, query.vector, query.limit, filterJson)
                parseScoredRecords(json, query.scoreThreshold)
            }
        }
    }

    override suspend fun count(query: RecordQuery.Count): Long {
        mutex.withLock {
            return withContext(dispatcher) {
                val current = requireHandle()
                var filter = query.filter
                if (query.recordTypes != null && query.recordTypes!!.isNotEmpty()) {
                    val typeFilter = RecordFilter.or(*query.recordTypes!!.map { RecordFilter.recordType(it) }.toTypedArray())
                    filter = if (filter != null) RecordFilter.and(filter, typeFilter) else typeFilter
                }
                val filterJson = FilterCompiler.compile(filter)
                NativeBridge.nativeCountFiltered(current, filterJson, query.exact)
            }
        }
    }

    override suspend fun flush() {
        mutex.withLock {
            withContext(dispatcher) {
                NativeBridge.nativeFlush(requireHandle())
            }
        }
    }

    override suspend fun optimize() {
        mutex.withLock {
            withContext(dispatcher) {
                NativeBridge.nativeOptimize(requireHandle())
            }
        }
    }

    private fun requireHandle(): Long {
        if (handle == 0L) {
            throw IllegalStateException("Record store is not initialized")
        }
        return handle
    }

    private fun retrieveSync(handle: Long, id: RecordId): Record? {
        val json = NativeBridge.nativeRetrieve(handle, "[\"${id.uuid}\"]")
        val records = parseRecords(json)
        return records.firstOrNull()
    }

    private fun parseRecords(json: String): List<Record> {
        val array = JSONArray(json)
        val records = mutableListOf<Record>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val pointId = obj.getString("id")
            val payloadObj = obj.getJSONObject("payload")
            val vector = if (obj.has("vector") && !obj.isNull("vector")) {
                val vectorArray = obj.getJSONArray("vector")
                FloatArray(vectorArray.length()) { vectorArray.getDouble(it).toFloat() }
            } else null

            val payloadMap = mutableMapOf<String, JsonValue>()
            val keys = payloadObj.keys()
            while (keys.hasNext()) {
                val key = keys.next() as String
                val value = payloadObj.get(key)
                payloadMap[key] = parseJsonValue(value)
            }

            val record = Record.fromFullPayload(RecordId.fromString(pointId), payloadMap)?.copy(vector = vector)
            records.add(record!!)
        }
        return records
    }

    private fun parseScoredRecords(json: String, scoreThreshold: Double?): List<ScoredRecord> {
        val array = JSONArray(json)
        val records = mutableListOf<ScoredRecord>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val score = obj.getDouble("score")
            if (scoreThreshold != null && score < scoreThreshold) continue
            val pointId = obj.getString("id")
            val payloadObj = obj.getJSONObject("payload")
            val payloadMap = mutableMapOf<String, JsonValue>()
            val keys = payloadObj.keys()
            while (keys.hasNext()) {
                val key = keys.next() as String
                val value = payloadObj.get(key)
                payloadMap[key] = parseJsonValue(value)
            }
            val record = Record.fromFullPayload(RecordId.fromString(pointId), payloadMap)
            if (record != null) {
                records.add(ScoredRecord(record, score))
            }
        }
        return records
    }

    private fun parseJsonValue(value: Any): JsonValue {
        return when (value) {
            is String -> JsonValue.fromString(value)
            is Number -> JsonValue.fromDouble(value.toDouble())
            is Boolean -> JsonValue.fromBoolean(value)
            is JSONObject -> {
                val map = mutableMapOf<String, JsonValue>()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next() as String
                    map[key] = parseJsonValue(value.get(key))
                }
                JsonValue.fromMap(map)
            }
            is JSONArray -> {
                val list = mutableListOf<JsonValue>()
                for (i in 0 until value.length()) {
                    list.add(parseJsonValue(value.get(i)))
                }
                JsonValue.fromList(list)
            }
            null -> JsonValue.nullValue()
            else -> throw IllegalArgumentException("Unsupported JSON value type: ${value.javaClass}")
        }
    }

    companion object {
        const val SHARD_CONFIG_FILE = "edge_config.json"

        fun isShardPresent(directory: File): Boolean = File(directory, SHARD_CONFIG_FILE).isFile
    }
}

/**
 * Loads the native library once. On Android this is via System.loadLibrary; on
 * the host JVM (unit tests) that throws UnsatisfiedLinkError, so the caller is
 * expected to have loaded the host .so explicitly beforehand.
 */
internal object NativeLoader {
    @Volatile
    private var attempted = false

    fun ensure() {
        if (attempted) return
        synchronized(this) {
            if (attempted) return
            attempted = true
            try {
                System.loadLibrary("edgememo_qdrant")
            } catch (_: UnsatisfiedLinkError) {
                // host JVM: the test relies on an explicitly loaded host library
            }
        }
    }
}

/**
 * Convert a [JsonValue] into a native org.json structure so nested objects
 * and arrays serialize as real JSON, not as `toString()` of a Kotlin map.
 * (Phase 13.3 fix: the previous `value.toJson()` produced a plain Kotlin
 * `Map`/`List`, which `JSONObject.put` stringified — silently corrupting
 * `metadata`, `chunkId` and any nested domain payload on the write path.)
 */
private fun JsonValue.toOrgJsonValue(): Any = when (this) {
    is JsonString -> value
    is JsonNumber -> value
    is JsonBoolean -> value
    JsonNull -> JSONObject.NULL
    is JsonArray -> JSONArray(value.map { it.toOrgJsonValue() })
    is JsonObject -> JSONObject().also { obj ->
        for ((key, child) in value) obj.put(key, child.toOrgJsonValue())
    }
}

private fun Map<String, JsonValue>.toJsonString(): String {
    val obj = JSONObject()
    for ((key, value) in this) {
        obj.put(key, value.toOrgJsonValue())
    }
    return obj.toString()
}

private fun Map<String, JsonValue>.toJsonObject(): JSONObject {
    val obj = JSONObject()
    for ((key, value) in this) {
        obj.put(key, value.toOrgJsonValue())
    }
    return obj
}