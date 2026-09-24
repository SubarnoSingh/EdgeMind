package com.example.EdgeMemo.data.conflict

import com.example.EdgeMemo.data.local.room.ConflictDao
import com.example.EdgeMemo.data.local.room.ConflictEntity
import com.example.EdgeMemo.data.conflict.ConflictMappers.toDomain
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.domain.conflict.ConflictRepository
import com.example.EdgeMemo.domain.conflict.ConflictResolutionState

object ConflictMappers {
    fun ConflictEntity.toDomain(): Conflict = Conflict(
        conflictId = conflictId,
        subjectKey = subjectKey,
        localMemoryId = localMemoryId,
        incomingMemoryId = incomingMemoryId,
        localTitle = localTitle,
        incomingTitle = incomingTitle,
        localContent = localContent,
        incomingContent = incomingContent,
        localVersion = localVersion,
        incomingVersion = incomingVersion,
        localContentHash = localContentHash,
        incomingContentHash = incomingContentHash,
        localOrigin = localOrigin,
        incomingOrigin = incomingOrigin,
        localAuthority = localAuthority,
        incomingAuthority = incomingAuthority,
        detectedAt = detectedAt,
        reason = reason,
        state = enumOf(state),
        resolvedAt = resolvedAt,
        resolution = resolution,
    )

    fun Conflict.toEntity(): ConflictEntity = ConflictEntity(
        conflictId = conflictId,
        subjectKey = subjectKey,
        localMemoryId = localMemoryId,
        incomingMemoryId = incomingMemoryId,
        localTitle = localTitle,
        incomingTitle = incomingTitle,
        localContent = localContent,
        incomingContent = incomingContent,
        localVersion = localVersion,
        incomingVersion = incomingVersion,
        localContentHash = localContentHash,
        incomingContentHash = incomingContentHash,
        localOrigin = localOrigin,
        incomingOrigin = incomingOrigin,
        localAuthority = localAuthority,
        incomingAuthority = incomingAuthority,
        detectedAt = detectedAt,
        reason = reason,
        state = state.name,
        resolvedAt = resolvedAt,
        resolution = resolution,
    )

    private inline fun <reified E : Enum<E>> enumOf(name: String): E =
        enumValues<E>().firstOrNull { it.name == name }
            ?: error("unknown $name")
}

class RoomConflictRepository(
    private val dao: ConflictDao,
) : ConflictRepository {

    override suspend fun list(): List<Conflict> = dao.listAll().map { it.toDomain() }

    override suspend fun get(conflictId: String): Conflict? =
        dao.getById(conflictId)?.toDomain()

    override suspend fun countUnresolved(): Long = dao.countUnresolved()
}