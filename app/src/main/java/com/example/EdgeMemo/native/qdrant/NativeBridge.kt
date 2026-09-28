package com.example.EdgeMemo.native.qdrant

internal object NativeBridge {
    external fun nativeCreate(path: String, dimension: Int): Long
    external fun nativeOpen(path: String): Long
    external fun nativeUpsert(handle: Long, id: String, vector: FloatArray)
    external fun nativeDelete(handle: Long, id: String)
    external fun nativeSearch(handle: Long, vector: FloatArray, limit: Int): Array<SearchResult>
    external fun nativeCount(handle: Long): Long
    external fun nativeOptimize(handle: Long)
    external fun nativeFlush(handle: Long)
    external fun nativeClose(handle: Long)

    // Phase-10 spike: record payload functions. JSON strings cross the JNI
    // boundary; the Rust side parses/serializes them with serde_json.
    external fun nativeUpsertWithPayload(handle: Long, id: String, vector: FloatArray, payload: String)
    external fun nativeRetrieve(handle: Long, idsJson: String): String
    external fun nativeScroll(handle: Long, filterJson: String?, limit: Int, offsetId: String?): String
    external fun nativeCountFiltered(handle: Long, filterJson: String?, exact: Boolean): Long
    external fun nativeCreatePayloadIndex(handle: Long, field: String, schema: String)
    external fun nativeSearchWithFilter(handle: Long, vector: FloatArray, limit: Int, filterJson: String?): String

    fun load() {
        System.loadLibrary("edgememo_qdrant")
    }
}