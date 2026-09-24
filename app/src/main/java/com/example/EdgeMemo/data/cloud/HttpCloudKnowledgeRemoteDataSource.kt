package com.example.EdgeMemo.data.cloud

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.data.remote.CloudHttpClient
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeBatch
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Real Phase-7 cloud → edge pull: Android → EdgeMind backend → Qdrant Cloud.
 * The cursor is the backend's opaque Qdrant next_page_offset and round-trips
 * unchanged. Item JSON mirrors `CloudKnowledgeItem` 1:1 so the existing
 * Phase 7 ingestor/classifier/conflict system consumes it unchanged.
 */
class HttpCloudKnowledgeRemoteDataSource(
    private val baseUrl: String,
    private val client: CloudHttpClient = CloudHttpClient(baseUrl),
) : CloudKnowledgeRemoteDataSource {

    override suspend fun pullKnowledge(cursor: String?): CloudKnowledgeBatch {
        if (baseUrl.isBlank()) {
            throw EdgeError.CloudUnavailable("no cloud knowledge backend is configured")
        }
        val path = buildString {
            append("/knowledge?limit=").append(PAGE_SIZE)
            if (cursor != null) {
                append("&cursor=").append(URLEncoder.encode(cursor, "UTF-8"))
            }
        }
        val text = try {
            client.getJson(path)
        } catch (e: CloudHttpClient.HttpFailure) {
            throw EdgeError.CloudUnavailable("cloud knowledge backend unavailable (${e.statusCode})")
        } catch (e: Exception) {
            throw EdgeError.CloudUnavailable("cloud knowledge backend unreachable")
        }
        return parse(text)
    }

    private fun parse(text: String): CloudKnowledgeBatch {
        return try {
            val json = JSONObject(text)
            val itemsJson = json.getJSONArray("items")
            val items = buildList {
                for (i in 0 until itemsJson.length()) {
                    val item = itemsJson.getJSONObject(i)
                    add(
                        CloudKnowledgeItem(
                            memoryId = item.getString("memoryId"),
                            subjectKey = item.optStringOrNull("subjectKey"),
                            title = item.optString("title"),
                            content = item.optString("content"),
                            contentHash = item.optString("contentHash"),
                            version = item.optInt("version", 1),
                            updatedAt = item.optLong("updatedAt", 0L),
                            origin = item.optString("origin", "CLOUD"),
                            authority = item.optStringOrNull("authority"),
                            supersedes = item.optStringOrNull("supersedes"),
                            tombstone = item.optBoolean("tombstone", false),
                            metadata = item.optMetadata(),
                        ),
                    )
                }
            }
            CloudKnowledgeBatch(
                items = items,
                nextCursor = json.optStringOrNull("nextCursor"),
            )
        } catch (e: Exception) {
            throw EdgeError.CloudUnavailable("cloud knowledge backend returned an invalid batch")
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = optString(key)
        return value.ifEmpty { null }
    }

    private fun JSONObject.optMetadata(): Map<String, String> {
        val metadata = optJSONObject("metadata") ?: return emptyMap()
        return buildMap {
            for (key in metadata.keys()) {
                val value = metadata.optString(key)
                if (value.isNotEmpty()) put(key, value)
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}
