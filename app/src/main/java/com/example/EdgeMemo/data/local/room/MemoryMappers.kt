package com.example.EdgeMemo.data.local.room

import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemorySensitivity
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision

object MemoryMappers {

    fun Memory.toEntity(): MemoryEntity = MemoryEntity(
        memoryId = memoryId,
        title = title,
        content = content,
        chunkId = chunkId,
        source = source,
        type = type.name,
        tags = tags,
        createdAt = createdAt,
        updatedAt = updatedAt,
        origin = origin.name,
        syncDecision = syncDecision.name,
        syncState = syncState.name,
        sensitivity = sensitivity.name,
        importance = importance,
        version = version,
        contentHash = contentHash,
        subjectKey = subjectKey,
        supersedes = supersedes,
        tombstone = tombstone,
        metadata = metadata,
        policyReason = policyReason,
        redactedTitle = redactedTitle,
        redactedContent = redactedContent,
        authority = authority,
    )

    fun MemoryEntity.toDomain(): Memory = Memory(
        memoryId = memoryId,
        title = title,
        content = content,
        chunkId = chunkId,
        source = source,
        type = enumOf<MemoryType>(type),
        tags = tags,
        createdAt = createdAt,
        updatedAt = updatedAt,
        origin = enumOf<MemoryOrigin>(origin),
        syncDecision = enumOf<SyncDecision>(syncDecision),
        syncState = enumOf<MemorySyncState>(syncState),
        sensitivity = enumOf<MemorySensitivity>(sensitivity),
        importance = importance,
        version = version,
        contentHash = contentHash,
        subjectKey = subjectKey,
        supersedes = supersedes,
        tombstone = tombstone,
        metadata = metadata,
        policyReason = policyReason,
        redactedTitle = redactedTitle,
        redactedContent = redactedContent,
        authority = authority,
    )

    private inline fun <reified E : Enum<E>> enumOf(name: String): E =
        enumValues<E>().firstOrNull { it.name == name }
            ?: error("unknown enum value '$name' for ${E::class.simpleName}")
}