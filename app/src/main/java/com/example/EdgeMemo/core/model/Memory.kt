package com.example.EdgeMemo.core.model

enum class MemoryType {
    DOCUMENT,
    NOTE,
    OBSERVATION,
    PROCEDURE,
    REPAIR,
    EVENT,
    CLOUD_KNOWLEDGE,
}

enum class MemoryOrigin {
    LOCAL,
    CLOUD,
    SYNCED,
}

enum class SyncDecision {
    LOCAL_ONLY,
    SYNC,
    SYNC_REDACTED,
}

enum class MemorySyncState {
    LOCAL,
    PENDING,
    SYNCED,
    FAILED,
}

enum class MemorySensitivity {
    STANDARD,
    SENSITIVE,
    RESTRICTED,
}

data class Memory(
    val memoryId: String,
    val title: String,
    val content: String,
    val chunkId: String?,
    val source: String,
    val type: MemoryType,
    val tags: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val origin: MemoryOrigin,
    val syncDecision: SyncDecision,
    val syncState: MemorySyncState,
    val sensitivity: MemorySensitivity,
    val importance: Int,
    val version: Int,
    val contentHash: String,
    val subjectKey: String?,
    val supersedes: String?,
    val tombstone: Boolean,
    val metadata: Map<String, String>,
    val policyReason: String? = null,
    val redactedTitle: String? = null,
    val redactedContent: String? = null,
    val authority: String? = null,
)

data class CreateMemoryInput(
    val title: String,
    val content: String,
    val type: MemoryType = MemoryType.NOTE,
    val tags: List<String> = emptyList(),
    val source: String = "USER_ENTRY",
    val importance: Int = 0,
    val subjectKey: String? = null,
    val chunkId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val sensitivity: MemorySensitivity = MemorySensitivity.STANDARD,
    val scope: String? = null,
    val userSyncChoice: SyncDecision? = null,
)

data class RetrievedMemory(
    val memory: Memory,
    val score: Double,
)