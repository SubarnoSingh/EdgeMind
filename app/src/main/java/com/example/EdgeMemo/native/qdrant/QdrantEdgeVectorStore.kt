package com.example.EdgeMemo.native.qdrant

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Kotlin-side [`LocalVectorStore`] backed by the qdrant-edge Rust native
 * library through the narrow [`NativeBridge`] boundary.
 */
class QdrantEdgeVectorStore(
    private val directory: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalVectorStore {

    private val mutex = Mutex()
    private var handle: Long = 0L

    override suspend fun initialize(dimension: Int) {
        mutex.withLock {
            withContext(dispatcher) {
                NativeLoader.ensure()
                if (handle == 0L) {
                    directory.mkdirs()
                    handle = NativeBridge.nativeCreate(directory.absolutePath, dimension)
                }
            }
        }
    }

    override suspend fun open() {
        mutex.withLock {
            withContext(dispatcher) {
                NativeLoader.ensure()
                if (handle == 0L) {
                    handle = NativeBridge.nativeOpen(directory.absolutePath)
                }
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

    override suspend fun upsert(points: List<VectorPoint>) {
        mutex.withLock {
            withContext(dispatcher) {
                val current = requireHandle()
                for (point in points) {
                    NativeBridge.nativeUpsert(current, point.id, point.vector)
                }
            }
        }
    }

    override suspend fun search(vector: FloatArray, limit: Int): List<SearchResult> {
        mutex.withLock {
            return withContext(dispatcher) {
                NativeBridge.nativeSearch(requireHandle(), vector, limit).toList()
            }
        }
    }

    override suspend fun delete(id: String) {
        mutex.withLock {
            withContext(dispatcher) {
                NativeBridge.nativeDelete(requireHandle(), id)
            }
        }
    }

    override suspend fun count(): Long {
        mutex.withLock {
            return withContext(dispatcher) {
                NativeBridge.nativeCount(requireHandle())
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
            throw IllegalStateException("LocalVectorStore is not initialized")
        }
        return handle
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