package com.example.EdgeMemo.core.document

/**
 * Identifies a document selected through the Android document APIs. Only
 * metadata is held here; the bytes are opened lazily through a reader.
 */
data class DocumentSource(
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long? = null,
) {
    val extension: String
        get() = displayName.substringAfterLast('.', "").lowercase()

    val title: String
        get() = displayName.substringBeforeLast('.', displayName).ifBlank { displayName }
}
