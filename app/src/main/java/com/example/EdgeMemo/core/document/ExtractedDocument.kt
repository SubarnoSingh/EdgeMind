package com.example.EdgeMemo.core.document

/**
 * A coherent region of a document. [page] is populated for paginated formats
 * (PDF); [section] is populated for formats that expose structure (Markdown
 * heading path). Chunking preserves these so citations can point at the source.
 */
data class DocumentBlock(
    val text: String,
    val page: Int? = null,
    val section: String? = null,
)

data class ExtractedDocument(
    val title: String,
    val blocks: List<DocumentBlock>,
    val metadata: Map<String, String> = emptyMap(),
) {
    val hasText: Boolean
        get() = blocks.any { it.text.isNotBlank() }
}
