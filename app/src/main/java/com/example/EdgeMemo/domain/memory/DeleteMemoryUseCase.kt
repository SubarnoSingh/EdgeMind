package com.example.EdgeMemo.domain.memory

class DeleteMemoryUseCase(private val repository: MemoryRepository) {
    suspend operator fun invoke(memoryId: String) = repository.delete(memoryId)
}