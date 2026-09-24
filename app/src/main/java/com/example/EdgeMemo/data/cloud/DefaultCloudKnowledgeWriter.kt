package com.example.EdgeMemo.data.cloud

import androidx.room.withTransaction
import com.example.EdgeMemo.ai.embedding.EmbeddingService
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.local.room.MemoryDao
import com.example.EdgeMemo.data.local.room.MemoryMappers.toEntity
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeWriter
import com.example.EdgeMemo.native.qdrant.LocalVectorStore
import com.example.EdgeMemo.native.qdrant.QdrantNativeException
import com.example.EdgeMemo.native.qdrant.VectorPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Applies accepted cloud knowledge to the edge. Vector first, Room metadata
 * second (same order as the memory repository) with best-effort vector
 * rollback on metadata failure. Cloud rows are type CLOUD_KNOWLEDGE, origin
 * CLOUD, syncState SYNCED and NEVER enter the sync outbox — they arrived from
 * the cloud, they are not pushed back.
 */
class DefaultCloudKnowledgeWriter(
    private val dao: MemoryDao,
    private val database: EdgeMindDatabase,
    private val vectorStore: LocalVectorStore,
    private val embeddingService: EmbeddingService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CloudKnowledgeWriter {

    override suspend fun applyNew(item: CloudKnowledgeItem): Memory = withContext(dispatcher) {
        val memory = Memory(
            memoryId = item.memoryId,
            title = item.title,
            content = item.content,
            chunkId = null,
            source = "CLOUD",
            type = MemoryType.CLOUD_KNOWLEDGE,
            tags = listOfNotNull(item.authority).ifEmpty { emptyList() },
            createdAt = item.updatedAt,
            updatedAt = item.updatedAt,
            origin = MemoryOrigin.CLOUD,
            syncDecision = SyncDecision.SYNC,
            syncState = MemorySyncState.SYNCED,
            sensitivity = com.example.EdgeMemo.core.model.MemorySensitivity.STANDARD,
            importance = 0,
            version = item.version,
            contentHash = item.contentHash,
            subjectKey = item.subjectKey,
            supersedes = item.supersedes,
            tombstone = false,
            metadata = item.metadata,
            authority = item.authority,
        )
        upsertVectorAndRoom(memory)
        memory
    }

    override suspend fun applyUpdate(item: CloudKnowledgeItem, local: Memory): Memory {
        val memory = local.copy(
            title = item.title,
            content = item.content,
            type = MemoryType.CLOUD_KNOWLEDGE,
            updatedAt = item.updatedAt,
            origin = MemoryOrigin.CLOUD,
            syncDecision = SyncDecision.SYNC,
            syncState = MemorySyncState.SYNCED,
            version = item.version,
            contentHash = item.contentHash,
            subjectKey = item.subjectKey,
            tombstone = false,
            metadata = item.metadata.ifEmpty { local.metadata },
            authority = item.authority,
        )
        return withContext(dispatcher) {
            upsertVectorAndRoom(memory)
            memory
        }
    }

    override suspend fun applySupersede(item: CloudKnowledgeItem): Memory = withContext(dispatcher) {
        // The superseding record becomes new active knowledge; the superseded
        // target stays as history and is excluded from retrieval by the
        // existing supersededIds() filter.
        applyNew(item)
    }

    override suspend fun applyTombstone(item: CloudKnowledgeItem, local: Memory) {
        val tombstoned = local.copy(
            tombstone = true,
            updatedAt = item.updatedAt,
            version = maxOf(local.version, item.version),
            syncState = MemorySyncState.SYNCED,
        )
        withContext(dispatcher) {
            // The row remains as historical evidence; its vector stops serving
            // offline retrieval (rows are filtered by tombstone in Room paths too).
            try {
                dao.update(tombstoned.toEntity())
            } catch (e: Exception) {
                throw EdgeError.LocalStorageError("failed to tombstone memory metadata: ${e.message}", e)
            }
            try {
                vectorStore.ensureReady(embeddingService.dimension)
                vectorStore.delete(item.memoryId)
            } catch (_: Exception) {
                // best-effort: metadata already recorded the tombstone
            }
        }
    }

    override suspend fun upsertVectorAndRoom(memory: Memory) {
        val embedding = try {
            embeddingService.embed(
                buildString {
                    append(memory.title)
                    if (memory.title.isNotEmpty() && memory.content.isNotEmpty()) append("\n")
                    append(memory.content)
                },
            )
        } catch (e: Exception) {
            throw EdgeError.EmbeddingError("cloud knowledge embedding failed: ${e.message}", e)
        }

        vectorStore.ensureReady(embeddingService.dimension)
        try {
            vectorStore.upsert(listOf(VectorPoint(memory.memoryId, embedding)))
        } catch (e: QdrantNativeException) {
            throw EdgeError.QdrantError("cloud vector upsert failed: ${e.message}", e)
        } catch (e: Exception) {
            throw EdgeError.QdrantError("cloud vector upsert failed: ${e.message}", e)
        }

        try {
            database.withTransaction {
                dao.upsert(memory.toEntity())
            }
        } catch (e: Exception) {
            try {
                vectorStore.delete(memory.memoryId)
            } catch (_: Exception) {
                // best-effort rollback
            }
            throw EdgeError.LocalStorageError("failed to persist cloud memory metadata: ${e.message}", e)
        }
    }
}