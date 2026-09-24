package com.example.EdgeMemo.data.document

import com.example.EdgeMemo.core.document.DocumentSource
import com.example.EdgeMemo.core.document.ExtractedDocument
import java.io.InputStream

/**
 * Extracts structured text from a document byte stream. Implementations must be
 * real parsers; they must never fabricate text that is not in the source.
 */
interface DocumentExtractor {
    val supportedMimeTypes: Set<String>
    val supportedExtensions: Set<String>

    fun supports(source: DocumentSource): Boolean =
        (source.mimeType != null && source.mimeType.lowercase() in supportedMimeTypes) ||
            source.extension in supportedExtensions

    suspend fun extract(input: InputStream, source: DocumentSource): ExtractedDocument
}
