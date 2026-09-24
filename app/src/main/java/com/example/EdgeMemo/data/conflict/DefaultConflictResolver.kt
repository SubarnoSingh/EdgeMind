package com.example.EdgeMemo.data.conflict

import androidx.room.withTransaction
import com.example.EdgeMemo.ai.embedding.EmbeddingService
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemorySensitivity
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.data.local.room.ConflictDao
import com.example.EdgeMemo.data.local.room.ConflictEntity
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.local.room.MemoryDao
import com.example.EdgeMemo.data.local.room.MemoryMappers.toEntity
import com.example.EdgeMemo.data.conflict.ConflictMappers.toDomain
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.domain.conflict.ConflictResolver
import com.example.EdgeMemo.domain.conflict.ConflictResolutionState
import com.example.EdgeMemo.native.qdrant.LocalVectorStore
import com.example.EdgeMemo.native.qdrant.QdrantNativeException
import com.example.EdgeMemo.native.qdrant.VectorPoint

/**
 * Deterministic conflict resolution. Every outcome persists [note] and keeps
 * evidence: keep-local leaves the local record untouched; keep-cloud makes the
 * incoming record active (tombstoning a divergent local record as history when
 * it is a distinct id) and keeps both sides in the conflict row; dismiss keeps
 * both records active. There is deliberately no MERGE.
 */
class DefaultConflictResolver(
    private val conflictDao: ConflictDao,
    private val memoryDao: MemoryDao,
    private val database: EdgeMindDatabase,
    private val embeddingService: EmbeddingService,
    private val vectorStore: LocalVectorStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : ConflictResolver {

    override suspend fun keepLocal(conflictId: String, note: String?): Conflict {
        val row = unresolvedOrThrow(conflictId)
        val resolved = row.copy(
            state = ConflictResolutionState.RESOLVED_LOCAL.name,
            resolvedAt = clock(),
            resolution = note ?: "kept local",
        )
        conflictDao.update(resolved)
        return resolved.toDomain()
    }

    override suspend fun keepCloud(conflictId: String, note: String?): Conflict {
        val row = unresolvedOrThrow(conflictId)
        val incoming = buildIncomingMemory(row)

        // Vector first, then metadata (single transaction with history + conflict).
        val embedding = try {
            embeddingService.embed("${incoming.title}\n${incoming.content}")
        } catch (e: Exception) {
            throw EdgeError.EmbeddingError("conflict resolution embedding failed: ${e.message}", e)
        }
        vectorStore.ensureReady(embeddingService.dimension)
        try {
            vectorStore.upsert(listOf(VectorPoint(incoming.memoryId, embedding)))
        } catch (e: QdrantNativeException) {
            throw EdgeError.QdrantError("conflict resolution vector upsert failed: ${e.message}", e)
        } catch (e: Exception) {
            throw EdgeError.QdrantError("conflict resolution vector upsert failed: ${e.message}", e)
        }

        val resolved = row.copy(
            state = ConflictResolutionState.RESOLVED_CLOUD.name,
            resolvedAt = clock(),
            resolution = note ?: "accepted cloud",
        )
        try {
            database.withTransaction {
                if (row.localMemoryId != null && row.localMemoryId != row.incomingMemoryId) {
                    // diverged record identity: the accepted cloud record becomes
                    // active and the old local record is tombstoned as history
                    memoryDao.getById(row.localMemoryId)?.let { old ->
                        memoryDao.update(old.copy(tombstone = true))
                    }
                }
                memoryDao.upsert(incoming.toEntity())
                conflictDao.update(resolved)
            }
        } catch (e: Exception) {
            try {
                vectorStore.delete(incoming.memoryId)
            } catch (_: Exception) {
                // best-effort rollback
            }
            throw EdgeError.LocalStorageError("failed to persist conflict resolution: ${e.message}", e)
        }

        if (row.localMemoryId != null && row.localMemoryId != row.incomingMemoryId) {
            try {
                vectorStore.delete(row.localMemoryId)
            } catch (_: Exception) {
                // the tombstoned record no longer serves retrieval
            }
        }
        return resolved.toDomain()
    }

    override suspend fun dismiss(conflictId: String, note: String?): Conflict {
        val row = unresolvedOrThrow(conflictId)
        val resolved = row.copy(
            state = ConflictResolutionState.DISMISSED.name,
            resolvedAt = clock(),
            resolution = note ?: "kept both, reviewed",
        )
        conflictDao.update(resolved)
        return resolved.toDomain()
    }

    private suspend fun unresolvedOrThrow(conflictId: String): ConflictEntity {
        val row = conflictDao.getById(conflictId)
            ?: throw EdgeError.ConflictNotFound(conflictId)
        require(row.state == ConflictResolutionState.UNRESOLVED.name) {
            "conflict $conflictId is already resolved (${row.state})"
        }
        return row
    }

    private fun buildIncomingMemory(row: ConflictEntity): Memory = Memory(
        memoryId = row.incomingMemoryId ?: throw EdgeError.ConflictNotFound(row.conflictId),
        title = row.incomingTitle,
        content = row.incomingContent,
        chunkId = null,
        source = "CLOUD",
        type = MemoryType.CLOUD_KNOWLEDGE,
        tags = listOfNotNull(row.incomingAuthority),
        createdAt = row.detectedAt,
        updatedAt = row.detectedAt,
        origin = MemoryOrigin.CLOUD,
        syncDecision = SyncDecision.SYNC,
        syncState = MemorySyncState.SYNCED,
        sensitivity = MemorySensitivity.STANDARD,
        importance = 0,
        version = row.incomingVersion ?: 1,
        contentHash = row.incomingContentHash,
        subjectKey = row.subjectKey.ifBlank { null },
        supersedes = null,
        tombstone = false,
        metadata = emptyMap(),
        authority = row.incomingAuthority,
    )
}