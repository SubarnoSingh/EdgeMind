package com.example.EdgeMemo.domain.memory

import com.example.EdgeMemo.core.model.Memory

class ListMemoriesUseCase(private val repository: MemoryRepository) {
    suspend operator fun invoke(): List<Memory> = repository.list()
}