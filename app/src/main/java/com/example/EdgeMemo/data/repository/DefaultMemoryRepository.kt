package com.example.EdgeMemo.data.repository

import com.example.EdgeMemo.ai.embedding.EmbeddingService
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.RetrievedMemory
import com.example.EdgeMemo.data.local.room.MemoryDao
import com.example.EdgeMemo.data.local.room.MemoryMappers.toDomain
import com.example.EdgeMemo.data.local.room.MemoryMappers.toEntity
import com.example.EdgeMemo.data.policy.DefaultPolicyEngine
import com.example.EdgeMemo.data.policy.DefaultRedactionService
import com.example.EdgeMemo.domain.memory.MemoryRepository
import com.example.EdgeMemo.domain.memory.MemoryWritePhase
import com.example.EdgeMemo.domain.policy.PolicyEngine
import com.example.EdgeMemo.core.policy.PolicyInput
import com.example.EdgeMemo.domain.sync.SyncOutboxWriter
import com.example.EdgeMemo.native.qdrant.LocalVectorStore
import com.example.EdgeMemo.native.qdrant.QdrantNativeException
import com.example.EdgeMemo.native.qdrant.VectorPoint
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DefaultMemoryRepository(
    private val dao: MemoryDao,
    private val vectorStore: LocalVectorStore,
    private val embeddingService: EmbeddingService,
    private val policyEngine: PolicyEngine = DefaultPolicyEngine(DefaultRedactionService()),
    private val outboxWriter: SyncOutboxWriter? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MemoryRepository {

    private val whitespaceRegex = Regex("\\s+")

    override suspend fun create(input: CreateMemoryInput): Memory = withContext(dispatcher) {
        val title = normalize(input.title)
        val content = normalize(input.content)
        if (title.isEmpty() && content.isEmpty()) {
            throw EdgeError.InvalidInput("memory title and content are both empty")
        }

        val memory = buildMemory(input, title, content, clock())
        val embedding = embedFor(memory)

        vectorStore.ensureReady(embeddingService.dimension)
        upsertOrThrow(memory.memoryId, embedding)

        val enqueued = try {
            outboxWriter?.insertMemory(memory) ?: run {
                dao.insert(memory.toEntity())
                false
            }
        } catch (e: Exception) {
            rollbackVector(memory.memoryId)
            throw EdgeError.LocalStorageError("failed to persist memory metadata: ${e.message}", e)
        }
        if (enqueued) memory.copy(syncState = MemorySyncState.PENDING) else memory
    }

    override suspend fun createAll(
        inputs: List<CreateMemoryInput>,
        onPhase: (MemoryWritePhase) -> Unit,
    ): List<Memory> = withContext(dispatcher) {
        if (inputs.isEmpty()) return@withContext emptyList()

        val now = clock()
        val memories = inputs.map { input ->
            val title = normalize(input.title)
            val content = normalize(input.content)
            if (title.isEmpty() && content.isEmpty()) {
                throw EdgeError.InvalidInput("memory title and content are both empty")
            }
            buildMemory(input, title, content, now)
        }

        onPhase(MemoryWritePhase.EMBEDDING)
        val embeddings = memories.map { embedFor(it) }

        onPhase(MemoryWritePhase.STORING)
        vectorStore.ensureReady(embeddingService.dimension)
        try {
            vectorStore.upsert(
                memories.mapIndexed { index, memory ->
                    VectorPoint(memory.memoryId, embeddings[index])
                },
            )
        } catch (e: QdrantNativeException) {
            throw EdgeError.QdrantError("vector upsert failed: ${e.message}", e)
        } catch (e: Exception) {
            throw EdgeError.QdrantError("vector upsert failed: ${e.message}", e)
        }

        val enqueuedFlags = try {
            if (outboxWriter != null) {
                outboxWriter.insertMemories(memories)
            } else {
                dao.insertAll(memories.map { it.toEntity() })
                emptyList()
            }
        } catch (e: Exception) {
            rollbackVectors(memories.map { it.memoryId })
            throw EdgeError.LocalStorageError("failed to persist document metadata: ${e.message}", e)
        }
        memories.mapIndexed { index, memory ->
            if (enqueuedFlags.getOrElse(index) { false }) {
                memory.copy(syncState = MemorySyncState.PENDING)
            } else {
                memory
            }
        }
    }

    override suspend fun update(memory: Memory): Memory = withContext(dispatcher) {
        val existing = dao.getById(memory.memoryId)
            ?: throw EdgeError.MemoryNotFound(memory.memoryId)
        val now = clock()
        val policy = policyEngine.evaluate(
            PolicyInput(
                title = memory.title,
                content = memory.content,
                type = memory.type,
                tags = memory.tags,
                sensitivity = memory.sensitivity,
                importance = memory.importance,
                scope = memory.metadata[META_SCOPE],
                // preserve the previous decision as the effective user choice so an
                // edit never silently reclassifies an intentional user decision; hard
                // privacy rules (access info, RESTRICTED, personal scope) still win.
                userSyncChoice = existing.toDomain().syncDecision,
                metadata = memory.metadata,
            ),
        )
        val updated = memory.copy(
            version = existing.version + 1,
            updatedAt = now,
            contentHash = contentHash(memory.title, memory.content),
            syncDecision = policy.syncDecision,
            policyReason = policy.reason,
            redactedTitle = policy.redacted?.title,
            redactedContent = policy.redacted?.content,
        )

        val embedding = embedFor(updated)
        vectorStore.ensureReady(embeddingService.dimension)
        upsertOrThrow(updated.memoryId, embedding)

        val enqueued = try {
            outboxWriter?.updateMemory(updated) ?: run {
                dao.update(updated.toEntity())
                false
            }
        } catch (e: Exception) {
            rollbackVector(updated.memoryId)
            throw EdgeError.LocalStorageError("failed to update memory metadata: ${e.message}", e)
        }
        if (enqueued) {
            updated.copy(syncState = MemorySyncState.PENDING)
        } else {
            updated.copy(syncState = MemorySyncState.LOCAL)
        }
    }

    override suspend fun delete(memoryId: String) = withContext(dispatcher) {
        try {
            dao.deleteById(memoryId)
        } catch (e: Exception) {
            throw EdgeError.LocalStorageError("failed to delete memory metadata: ${e.message}", e)
        }
        outboxWriter?.cancelForMemory(memoryId)
        try {
            vectorStore.ensureReady(embeddingService.dimension)
            vectorStore.delete(memoryId)
        } catch (e: QdrantNativeException) {
            throw EdgeError.QdrantError("failed to delete vector: ${e.message}", e)
        } catch (e: Exception) {
            throw EdgeError.QdrantError("failed to delete vector: ${e.message}", e)
        }
    }

    override suspend fun get(memoryId: String): Memory? = withContext(dispatcher) {
        dao.getById(memoryId)?.takeUnless { it.tombstone }?.toDomain()
    }

    override suspend fun list(): List<Memory> = withContext(dispatcher) {
        dao.listAll().filterNot { it.tombstone }.map { it.toDomain() }
    }

    override suspend fun search(query: String, limit: Int): List<RetrievedMemory> =
        withContext(dispatcher) {
            val normalized = normalize(query)
            if (normalized.isEmpty()) {
                throw EdgeError.InvalidInput("search query is empty")
            }
            val queryVector = try {
                embeddingService.embed(normalized)
            } catch (e: EdgeError) {
                throw EdgeError.EmbeddingError(e.message ?: "query embedding failed", e)
            } catch (e: Exception) {
                throw EdgeError.EmbeddingError("query embedding failed: ${e.message}", e)
            }

            vectorStore.ensureReady(embeddingService.dimension)
            val hits = try {
                vectorStore.search(queryVector, limit.coerceAtLeast(1) * 4)
            } catch (e: QdrantNativeException) {
                throw EdgeError.QdrantError("vector search failed: ${e.message}", e)
            } catch (e: Exception) {
                throw EdgeError.QdrantError("vector search failed: ${e.message}", e)
            }

            if (hits.isEmpty()) return@withContext emptyList()

            val byId = dao.getByIds(hits.map { it.id }).associateBy { it.memoryId }
            val retrieved = ArrayList<RetrievedMemory>(hits.size)
            for (hit in hits) {
                val entity = byId[hit.id]?.takeUnless { it.tombstone } ?: continue
                retrieved.add(RetrievedMemory(memory = entity.toDomain(), score = hit.score))
            }
            retrieved.take(limit)
        }

    override suspend fun count(): Long = withContext(dispatcher) {
        dao.count()
    }

    private fun buildMemory(input: CreateMemoryInput, title: String, content: String, now: Long): Memory {
        val metadata = if (input.scope != null) {
            input.metadata + (META_SCOPE to input.scope)
        } else {
            input.metadata
        }
        val policy = policyEngine.evaluate(
            PolicyInput(
                title = title,
                content = content,
                type = input.type,
                tags = input.tags,
                sensitivity = input.sensitivity,
                importance = input.importance,
                scope = input.scope,
                userSyncChoice = input.userSyncChoice,
                metadata = metadata,
            ),
        )
        return Memory(
            memoryId = idGenerator(),
            title = title,
            content = content,
            chunkId = input.chunkId,
            source = input.source,
            type = input.type,
            tags = input.tags,
            createdAt = now,
            updatedAt = now,
            origin = MemoryOrigin.LOCAL,
            syncDecision = policy.syncDecision,
            syncState = MemorySyncState.LOCAL,
            sensitivity = input.sensitivity,
            importance = input.importance,
            version = 1,
            contentHash = contentHash(title, content),
            subjectKey = input.subjectKey,
            supersedes = null,
            tombstone = false,
            metadata = metadata,
            policyReason = policy.reason,
            redactedTitle = policy.redacted?.title,
            redactedContent = policy.redacted?.content,
        )
    }

    private suspend fun embedFor(memory: Memory): FloatArray {
        val text = buildString {
            append(memory.title)
            if (memory.title.isNotEmpty() && memory.content.isNotEmpty()) append("\n")
            append(memory.content)
        }
        return try {
            embeddingService.embed(text)
        } catch (e: EdgeError) {
            throw EdgeError.EmbeddingError(e.message ?: "embedding failed", e)
        } catch (e: Exception) {
            throw EdgeError.EmbeddingError("embedding failed: ${e.message}", e)
        }
    }

    private suspend fun upsertOrThrow(memoryId: String, embedding: FloatArray) {
        try {
            vectorStore.upsert(listOf(VectorPoint(memoryId, embedding)))
        } catch (e: QdrantNativeException) {
            throw EdgeError.QdrantError("vector upsert failed: ${e.message}", e)
        } catch (e: Exception) {
            throw EdgeError.QdrantError("vector upsert failed: ${e.message}", e)
        }
    }

    private suspend fun rollbackVector(memoryId: String) {
        try {
            vectorStore.ensureReady(embeddingService.dimension)
            vectorStore.delete(memoryId)
        } catch (_: Exception) {
            // best-effort; metadata insert already failed and the error was reported
        }
    }

    private suspend fun rollbackVectors(memoryIds: List<String>) {
        try {
            vectorStore.ensureReady(embeddingService.dimension)
        } catch (_: Exception) {
            return
        }
        for (memoryId in memoryIds) {
            try {
                vectorStore.delete(memoryId)
            } catch (_: Exception) {
                // best-effort; keep removing the remaining vectors
            }
        }
    }

    private fun normalize(text: String): String = text.trim().replace(whitespaceRegex, " ")

    private fun contentHash(title: String, content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$title\n$content".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** Scope is remembered on the memory as policy-relevant metadata. */
private const val META_SCOPE = "scope"