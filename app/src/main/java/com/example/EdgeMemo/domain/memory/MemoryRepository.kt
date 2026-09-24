package com.example.EdgeMemo.domain.memory

import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.RetrievedMemory

/**
 * Domain-level phase reported while writing memories, so callers can surface
 * honest ingestion progress without knowing about storage internals.
 */
enum class MemoryWritePhase {
    EMBEDDING,
    STORING,
}

interface MemoryRepository {

    suspend fun create(input: CreateMemoryInput): Memory

    /**
     * Writes a batch of memories atomically: either every memory is embedded and
     * persisted, or none is. Used by document ingestion so a partial document is
     * never left behind.
     */
    suspend fun createAll(
        inputs: List<CreateMemoryInput>,
        onPhase: (MemoryWritePhase) -> Unit = {},
    ): List<Memory>

    suspend fun update(memory: Memory): Memory

    suspend fun delete(memoryId: String)

    suspend fun get(memoryId: String): Memory?

    suspend fun list(): List<Memory>

    suspend fun search(query: String, limit: Int = 20): List<RetrievedMemory>

    suspend fun count(): Long
}
