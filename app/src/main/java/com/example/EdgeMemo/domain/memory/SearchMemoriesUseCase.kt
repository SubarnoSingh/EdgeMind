package com.example.EdgeMemo.domain.memory

import com.example.EdgeMemo.core.model.RetrievedMemory

class SearchMemoriesUseCase(private val repository: MemoryRepository) {
    suspend operator fun invoke(query: String, limit: Int = 20): List<RetrievedMemory> =
        repository.search(query, limit)
}