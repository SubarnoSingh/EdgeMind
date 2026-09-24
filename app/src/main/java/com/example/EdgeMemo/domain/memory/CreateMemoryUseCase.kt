package com.example.EdgeMemo.domain.memory

import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.Memory

class CreateMemoryUseCase(private val repository: MemoryRepository) {
    suspend operator fun invoke(input: CreateMemoryInput): Memory = repository.create(input)
}