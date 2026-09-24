package com.example.EdgeMemo.domain.retrieval

import com.example.EdgeMemo.core.retrieval.RetrievalQuery
import com.example.EdgeMemo.core.retrieval.RetrievalResult
import com.example.EdgeMemo.core.retrieval.RetrievalService

class RetrieveMemoriesUseCase(private val service: RetrievalService) {
    suspend operator fun invoke(query: RetrievalQuery): RetrievalResult = service.retrieve(query)
}
