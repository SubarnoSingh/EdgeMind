package com.example.EdgeMemo.domain.document

/**
 * Real, observable stages of document ingestion. Each value corresponds to work
 * that has actually started; there are no fabricated percentages.
 */
enum class IngestionStage {
    IDLE,
    SELECTING,
    EXTRACTING,
    CHUNKING,
    EMBEDDING,
    STORING,
    COMPLETED,
    FAILED,
}
