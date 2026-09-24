package com.example.EdgeMemo.domain.rag

import com.example.EdgeMemo.core.rag.RagRequest
import com.example.EdgeMemo.core.rag.RagResponse
import com.example.EdgeMemo.core.rag.RagStage

class AskQuestionUseCase(private val ragService: RagService) {
    suspend operator fun invoke(
        request: RagRequest,
        onStage: (RagStage) -> Unit = {},
    ): RagResponse = ragService.answer(request, onStage)
}
