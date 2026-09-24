package com.example.EdgeMemo.core.model

/**
 * Well-known keys for [Memory.metadata]. Shared by ingestion, retrieval and
 * citation mapping so source references stay consistent across layers.
 */
object MemoryMetadataKeys {
    const val DOCUMENT_ID = "documentId"
    const val DOCUMENT_TITLE = "documentTitle"
    const val SOURCE_URI = "sourceUri"
    const val SOURCE_NAME = "sourceName"
    const val CHUNK_INDEX = "chunkIndex"
    const val CHUNK_COUNT = "chunkCount"
    const val FORMAT = "format"
    const val PAGE = "page"
    const val SECTION = "section"
}
