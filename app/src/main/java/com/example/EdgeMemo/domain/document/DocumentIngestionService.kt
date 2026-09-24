package com.example.EdgeMemo.domain.document

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.document.DocumentChunker
import com.example.EdgeMemo.core.document.DocumentSource
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.MemoryMetadataKeys
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.data.document.ContentResolverDocumentReader
import com.example.EdgeMemo.data.document.DocumentExtractorRegistry
import com.example.EdgeMemo.domain.memory.MemoryRepository
import com.example.EdgeMemo.domain.memory.MemoryWritePhase
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates offline document ingestion:
 *
 * open → extract → normalize → chunk → embed → Qdrant Edge + Room.
 *
 * Every chunk becomes a real [com.example.EdgeMemo.core.model.Memory] of type
 * DOCUMENT, tagged with its source document, chunk index and (when available)
 * page and section. Writes are atomic through [MemoryRepository.createAll].
 */
class DocumentIngestionService(
    private val reader: ContentResolverDocumentReader,
    private val registry: DocumentExtractorRegistry,
    private val chunker: DocumentChunker,
    private val repository: MemoryRepository,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun ingest(
        source: DocumentSource,
        onStage: (IngestionStage) -> Unit = {},
    ): IngestionResult = withContext(dispatcher) {
        onStage(IngestionStage.EXTRACTING)
        val extractor = registry.require(source)
        val extracted = try {
            reader.open(source).use { input -> extractor.extract(input, source) }
        } catch (e: EdgeError) {
            throw e
        } catch (e: Exception) {
            throw EdgeError.InvalidDocument(
                "failed to read document ${source.displayName}: ${e.message}",
                e,
            )
        }

        onStage(IngestionStage.CHUNKING)
        val chunks = chunker.chunk(extracted.blocks)
        if (chunks.isEmpty()) {
            throw EdgeError.InvalidDocument(
                "no text could be extracted from ${source.displayName}",
            )
        }

        val documentId = idGenerator()
        val inputs = chunks.map { chunk ->
            CreateMemoryInput(
                title = chunkTitle(extracted.title, chunk.index),
                content = chunk.text,
                type = MemoryType.DOCUMENT,
                tags = buildList {
                    add(TAG_DOCUMENT)
                    if (source.extension.isNotEmpty()) add(source.extension)
                },
                source = source.displayName,
                chunkId = "$documentId#${chunk.index}",
                metadata = buildMap {
                    put(META_DOCUMENT_ID, documentId)
                    put(META_DOCUMENT_TITLE, extracted.title)
                    put(META_SOURCE_URI, source.uri)
                    put(META_SOURCE_NAME, source.displayName)
                    put(META_CHUNK_INDEX, chunk.index.toString())
                    put(META_CHUNK_COUNT, chunks.size.toString())
                    extracted.metadata["format"]?.let { put(META_FORMAT, it) }
                    chunk.page?.let { put(META_PAGE, it.toString()) }
                    chunk.section?.let { put(META_SECTION, it) }
                },
            )
        }

        val memories = repository.createAll(inputs) { phase ->
            when (phase) {
                MemoryWritePhase.EMBEDDING -> onStage(IngestionStage.EMBEDDING)
                MemoryWritePhase.STORING -> onStage(IngestionStage.STORING)
            }
        }

        onStage(IngestionStage.COMPLETED)
        IngestionResult(
            documentId = documentId,
            title = extracted.title,
            chunkCount = memories.size,
            memories = memories,
        )
    }

    private fun chunkTitle(documentTitle: String, index: Int): String =
        if (index == 0) documentTitle else "$documentTitle #${index + 1}"

    companion object {
        const val TAG_DOCUMENT = "document"

        const val META_DOCUMENT_ID = MemoryMetadataKeys.DOCUMENT_ID
        const val META_DOCUMENT_TITLE = MemoryMetadataKeys.DOCUMENT_TITLE
        const val META_SOURCE_URI = MemoryMetadataKeys.SOURCE_URI
        const val META_SOURCE_NAME = MemoryMetadataKeys.SOURCE_NAME
        const val META_CHUNK_INDEX = MemoryMetadataKeys.CHUNK_INDEX
        const val META_CHUNK_COUNT = MemoryMetadataKeys.CHUNK_COUNT
        const val META_FORMAT = MemoryMetadataKeys.FORMAT
        const val META_PAGE = MemoryMetadataKeys.PAGE
        const val META_SECTION = MemoryMetadataKeys.SECTION
    }
}
