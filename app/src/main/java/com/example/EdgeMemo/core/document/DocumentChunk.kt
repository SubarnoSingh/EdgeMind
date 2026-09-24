package com.example.EdgeMemo.core.document

/**
 * A deterministic slice of an extracted document, ready to be embedded and
 * stored as a [com.example.EdgeMemo.core.model.Memory].
 */
data class DocumentChunk(
    val index: Int,
    val text: String,
    val page: Int?,
    val section: String?,
)
