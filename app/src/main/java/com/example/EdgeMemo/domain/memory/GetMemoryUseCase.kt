package com.example.EdgeMemo.domain.memory

import com.example.EdgeMemo.core.model.Memory

/**
 * Read ONE stored memory by id through the active [MemoryRepository]
 * (Qdrant-native). Returns null when the record is absent or tombstoned —
 * the caller must render that honestly. UI Phase 4 uses it to show the real
 * resulting sync state of the local record after a conflict resolution.
 */
class GetMemoryUseCase(
    private val repository: MemoryRepository,
) {
    suspend operator fun invoke(memoryId: String): Memory? = repository.get(memoryId)
}
