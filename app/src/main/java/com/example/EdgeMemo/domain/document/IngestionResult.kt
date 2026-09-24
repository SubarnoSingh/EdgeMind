package com.example.EdgeMemo.domain.document

import com.example.EdgeMemo.core.model.Memory

data class IngestionResult(
    val documentId: String,
    val title: String,
    val chunkCount: Int,
    val memories: List<Memory>,
)
