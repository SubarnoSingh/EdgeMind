package com.example.EdgeMemo.core.common

sealed class EdgeError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidInput(message: String) : EdgeError(message)

    class EmbeddingError(message: String, cause: Throwable? = null) : EdgeError(message, cause)

    class LocalStorageError(message: String, cause: Throwable? = null) : EdgeError(message, cause)

    class QdrantError(message: String, cause: Throwable? = null) : EdgeError(message, cause)

    class MemoryNotFound(memoryId: String) : EdgeError("memory not found: $memoryId")

    class InvalidDocument(message: String, cause: Throwable? = null) : EdgeError(message, cause)

    /** No real cloud backend is available (Phase 7 remote is unimplemented). */
    class CloudUnavailable(message: String) : EdgeError(message)

    class ConflictNotFound(conflictId: String) : EdgeError("conflict not found: $conflictId")
}