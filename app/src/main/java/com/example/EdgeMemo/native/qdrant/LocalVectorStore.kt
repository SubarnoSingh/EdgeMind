package com.example.EdgeMemo.native.qdrant

data class VectorPoint(
    val id: String,
    val vector: FloatArray,
) {
    override fun equals(other: Any?): Boolean =
        other is VectorPoint && other.id == id && other.vector.contentEquals(vector)

    override fun hashCode(): Int = 31 * id.hashCode() + vector.contentHashCode()
}

interface LocalVectorStore {
    /** Create the shard at [dimension]. Throws if it already exists. */
    suspend fun initialize(dimension: Int)

    /** Reopen an existing shard. */
    suspend fun open()

    /** Create if fresh, otherwise reopen. Idempotent entry point for app restarts. */
    suspend fun ensureReady(dimension: Int)

    /** Release the native shard. */
    suspend fun close()

    suspend fun upsert(points: List<VectorPoint>)
    suspend fun search(vector: FloatArray, limit: Int): List<SearchResult>
    suspend fun delete(id: String)
    suspend fun count(): Long
    suspend fun optimize()
}