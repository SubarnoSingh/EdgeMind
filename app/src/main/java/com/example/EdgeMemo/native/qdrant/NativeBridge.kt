package com.example.EdgeMemo.native.qdrant

internal object NativeBridge {
    external fun nativeCreate(path: String, dimension: Int): Long
    external fun nativeOpen(path: String): Long
    external fun nativeUpsert(handle: Long, id: String, vector: FloatArray)
    external fun nativeDelete(handle: Long, id: String)
    external fun nativeSearch(handle: Long, vector: FloatArray, limit: Int): Array<SearchResult>
    external fun nativeCount(handle: Long): Long
    external fun nativeOptimize(handle: Long)
    external fun nativeClose(handle: Long)

    fun load() {
        System.loadLibrary("edgememo_qdrant")
    }
}