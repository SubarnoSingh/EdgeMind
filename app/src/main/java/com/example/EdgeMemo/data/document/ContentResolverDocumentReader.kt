package com.example.EdgeMemo.data.document

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.document.DocumentSource
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads document metadata and byte streams through the Android ContentResolver
 * (SAF). No network access and no extra permissions are required.
 */
class ContentResolverDocumentReader(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    suspend fun resolve(uri: Uri): DocumentSource = withContext(dispatcher) {
        var displayName: String? = null
        var size: Long? = null
        try {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) {
            // Providers such as file:// do not support querying; fall back below.
        }
        val name = displayName
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "document"
        DocumentSource(
            uri = uri.toString(),
            displayName = name,
            mimeType = resolver.getType(uri),
            sizeBytes = size,
        )
    }

    suspend fun open(source: DocumentSource): InputStream = withContext(dispatcher) {
        resolver.openInputStream(Uri.parse(source.uri))
            ?: throw EdgeError.InvalidDocument("could not open document: ${source.displayName}")
    }
}
