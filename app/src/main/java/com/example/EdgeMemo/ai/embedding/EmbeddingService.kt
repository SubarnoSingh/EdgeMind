package com.example.EdgeMemo.ai.embedding

interface EmbeddingService {
    val dimension: Int

    suspend fun embed(text: String): FloatArray
}