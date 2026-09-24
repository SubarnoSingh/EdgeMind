package com.example.EdgeMemo.core.retrieval

interface RetrievalService {
    suspend fun retrieve(query: RetrievalQuery): RetrievalResult
}