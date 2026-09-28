package com.example.EdgeMemo.phase10

import com.example.EdgeMemo.native.qdrant.NativeBridge
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Test-only bridge over the Phase-10 native record functions. It manages the
 * raw JNI handle directly — no production Kotlin classes are involved — so the
 * spike stays isolated and fully reversible.
 *
 * All records cross the JNI boundary as JSON strings; the Rust side
 * parses/serializes them with serde_json against the real qdrant-edge
 * payload API.
 */
class SpikeStore(private val directory: File) {

    var handle: Long = 0L
        private set

    val isOpen: Boolean
        get() = handle != 0L

    fun create(dimension: Int) {
        require(handle == 0L) { "spike store already open" }
        directory.mkdirs()
        handle = NativeBridge.nativeCreate(directory.absolutePath, dimension)
    }

    fun open() {
        require(handle == 0L) { "spike store already open" }
        handle = NativeBridge.nativeOpen(directory.absolutePath)
    }

    fun close() {
        if (handle != 0L) {
            NativeBridge.nativeClose(handle)
            handle = 0L
        }
    }

    fun flush() {
        requireOpen()
        NativeBridge.nativeFlush(handle)
    }

    fun createIndex(field: String, schema: String) {
        requireOpen()
        NativeBridge.nativeCreatePayloadIndex(handle, field, schema)
    }

    fun upsert(pointId: String, vector: FloatArray, payload: JSONObject) {
        requireOpen()
        NativeBridge.nativeUpsertWithPayload(handle, pointId, vector, payload.toString())
    }

    fun delete(pointId: String) {
        requireOpen()
        NativeBridge.nativeDelete(handle, pointId)
        NativeBridge.nativeFlush(handle)
    }

    fun retrieve(vararg pointIds: String): List<JSONObject> {
        requireOpen()
        val json = NativeBridge.nativeRetrieve(handle, JSONArray(pointIds.toList()).toString())
        return parseArray(json)
    }

    fun scroll(filterJson: String? = null, limit: Int = 50, offsetId: String? = null): List<JSONObject> {
        requireOpen()
        val json = NativeBridge.nativeScroll(handle, filterJson, limit, offsetId)
        return parseArray(json)
    }

    fun count(filterJson: String? = null, exact: Boolean = true): Long {
        requireOpen()
        return NativeBridge.nativeCountFiltered(handle, filterJson, exact)
    }

    fun search(vector: FloatArray, limit: Int, filterJson: String? = null): List<JSONObject> {
        requireOpen()
        val json = NativeBridge.nativeSearchWithFilter(handle, vector, limit, filterJson)
        return parseArray(json)
    }

    private fun parseArray(json: String): List<JSONObject> {
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun requireOpen() {
        require(handle != 0L) { "spike store is not open" }
    }
}
