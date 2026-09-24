package com.example.EdgeMemo.domain.memory

class GetMemoryCountUseCase(private val repository: MemoryRepository) {
    suspend operator fun invoke(): Long = repository.count()
}