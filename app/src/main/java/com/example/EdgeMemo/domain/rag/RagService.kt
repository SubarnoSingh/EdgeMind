package com.example.EdgeMemo.domain.rag

import com.example.EdgeMemo.core.rag.RagRequest
import com.example.EdgeMemo.core.rag.RagResponse
import com.example.EdgeMemo.core.rag.RagStage

interface RagService {
    suspend fun answer(request: RagRequest, onStage: (RagStage) -> Unit = {}): RagResponse
}
