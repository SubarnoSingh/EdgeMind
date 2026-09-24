package com.example.EdgeMemo.domain.document

import com.example.EdgeMemo.core.document.DocumentSource

class IngestDocumentUseCase(
    private val service: DocumentIngestionService,
) {
    suspend operator fun invoke(
        source: DocumentSource,
        onStage: (IngestionStage) -> Unit = {},
    ): IngestionResult = service.ingest(source, onStage)
}
