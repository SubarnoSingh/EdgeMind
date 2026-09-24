package com.example.EdgeMemo.data.document

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.document.DocumentSource

/**
 * Selects the extractor for a document source. Fails with a specific
 * [EdgeError.InvalidDocument] when no extractor supports the format.
 */
class DocumentExtractorRegistry(
    private val extractors: List<DocumentExtractor>,
) {
    fun find(source: DocumentSource): DocumentExtractor? =
        extractors.firstOrNull { it.supports(source) }

    fun require(source: DocumentSource): DocumentExtractor =
        find(source) ?: throw EdgeError.InvalidDocument(
            "unsupported document type: ${source.displayName} (${source.mimeType ?: "unknown mime"})",
        )

    val supportedMimeTypes: List<String>
        get() = extractors.flatMap { it.supportedMimeTypes }.distinct().sorted()
}
