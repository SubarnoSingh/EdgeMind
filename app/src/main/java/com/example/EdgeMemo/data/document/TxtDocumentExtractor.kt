package com.example.EdgeMemo.data.document

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.document.DocumentBlock
import com.example.EdgeMemo.core.document.DocumentSource
import com.example.EdgeMemo.core.document.ExtractedDocument
import com.example.EdgeMemo.core.document.TextNormalizer
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TxtDocumentExtractor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DocumentExtractor {

    override val supportedMimeTypes: Set<String> = setOf("text/plain")
    override val supportedExtensions: Set<String> = setOf("txt", "text", "log")

    override suspend fun extract(input: InputStream, source: DocumentSource): ExtractedDocument =
        withContext(dispatcher) {
            val raw = input.readBytes().toString(Charsets.UTF_8)
            val normalized = TextNormalizer.normalize(raw)
            if (normalized.isBlank()) {
                throw EdgeError.InvalidDocument("document is empty: ${source.displayName}")
            }
            val blocks = normalized.split(PARAGRAPH_BOUNDARY)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { DocumentBlock(text = it) }
            ExtractedDocument(
                title = source.title,
                blocks = blocks,
                metadata = mapOf("format" to "txt"),
            )
        }

    private companion object {
        val PARAGRAPH_BOUNDARY = Regex("\\n\\s*\\n")
    }
}
