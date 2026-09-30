package com.example.EdgeMemo.data.repository

import com.example.EdgeMemo.ai.embedding.EmbeddingService
import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.RetrievedMemory
import com.example.EdgeMemo.core.policy.PolicyInput
import com.example.EdgeMemo.core.record.LocalRecordStore
import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordQuery

import com.example.EdgeMemo.core.record.SyncState
import com.example.EdgeMemo.core.sync.ChangeDetectionOutcome
import com.example.EdgeMemo.core.sync.QdrantSyncEngine
import com.example.EdgeMemo.data.policy.DefaultPolicyEngine
import com.example.EdgeMemo.data.policy.DefaultRedactionService
import com.example.EdgeMemo.domain.memory.MemoryRepository
import com.example.EdgeMemo.domain.memory.MemoryWritePhase
import com.example.EdgeMemo.domain.policy.PolicyEngine
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 13.2 — the ACTIVE application memory repository, backed exclusively
 * by Qdrant Edge through [LocalRecordStore]. Room is not referenced anywhere
 * on this path.
 *
 * Every mutation is one durable record write into the single application
 * shard (vector + payload + envelope in the same point), immediately followed
 * by the frozen Phase-12 change detection ([QdrantSyncEngine.enqueueIfChanged])
 * so a policy-sanctioned mutation deterministically feeds the Qdrant-native
 * operation store. There is deliberately no second outbox, no legacy
 * `UPSERT-<memoryId>` identity and no dual-write: the Qdrant-native identity
 * (`UPSERT:<uuid>:<version>` / `TOMBSTONE:<uuid>:<version>`) is the only sync
 * identity this repository can produce.
 *
 * Semantics preserved from the legacy Room repository:
 *  - whitespace normalization and the same empty-input rejection;
 *  - policy evaluation at persistence time (hard rules win over user choice;
 *    an update preserves the previous decision as the user choice);
 *  - vector-before-metadata ordering concern disappears: one Qdrant point IS
 *    vector + metadata, so there is no cross-store rollback dance;
 *  - LOCAL_ONLY memories are stored locally and produce zero operations;
 *  - SYNC_REDACTED stores the redacted representation alongside the original;
 *    only the redacted form is ever sanctioned (detector-enforced);
 *  - returned copies mark PENDING exactly when an operation was enqueued;
 *  - tombstoned records are excluded from get/list/search.
 *
 * Deliberate, documented behavior changes (architecture-required):
 *  - content hash is the frozen [com.example.EdgeMemo.core.sync.CanonicalContentHash]
 *    over the domain payload (not the legacy SHA-256 of "title\ncontent");
 *    the change detector stamps the identical value on the stored record, so
 *    repository and sync agree on one identity;
 *  - delete is a TOMBSTONE (version bump + soft delete + deterministic
 *    TOMBSTONE operation), never a physical delete, so the Phase-12
 *    propagation and resurrection rules apply; deleting an already-deleted
 *    id is a no-op (idempotent);
 *  - count() counts ACTIVE records (the legacy count included tombstoned
 *    history rows, which made the UI chip disagree with the list).
 */
class QdrantRecordMemoryRepository(
    private val recordStore: LocalRecordStore,
    private val syncEngine: QdrantSyncEngine,
    private val embeddingService: EmbeddingService,
    private val policyEngine: PolicyEngine = DefaultPolicyEngine(DefaultRedactionService()),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MemoryRepository {

    private val whitespaceRegex = Regex("\\s+")

    // ------------------------------------------------------------------
    // Write path
    // ------------------------------------------------------------------

    override suspend fun create(input: CreateMemoryInput): Memory = withContext(dispatcher) {
        val now = clock()
        val memory = buildMemory(input, clock = now)
        persistNew(memory)
    }

    override suspend fun createAll(
        inputs: List<CreateMemoryInput>,
        onPhase: (MemoryWritePhase) -> Unit,
    ): List<Memory> = withContext(dispatcher) {
        if (inputs.isEmpty()) return@withContext emptyList()

        val now = clock()
        val memories = inputs.map { input -> buildMemory(input, clock = now) }

        onPhase(MemoryWritePhase.EMBEDDING)
        val vectors = memories.map { embedFor(it) }

        onPhase(MemoryWritePhase.STORING)
        awaitStore()
        recordStore.upsertBatch(
            memories.mapIndexed { index, memory ->
                MemoryRecordMapper.toRecord(memory, vectors[index])
            },
        )
        // Detection runs after the batched write so every chunk becomes a
        // deterministic operation identity, exactly as a single create does.
        memories.map { memory ->
            applyDetectionOutcome(memory)
        }
    }

    override suspend fun update(memory: Memory): Memory = withContext(dispatcher) {
        awaitStore()
        val existing = storedMemory(memory.memoryId)
            ?: throw EdgeError.MemoryNotFound(memory.memoryId)

        // Preserve the previous decision as the effective user choice so an
        // edit never silently reclassifies an intentional user decision;
        // hard privacy rules still win (identical rule to the legacy repo).
        val policy = policyEngine.evaluate(
            PolicyInput(
                title = memory.title,
                content = memory.content,
                type = memory.type,
                tags = memory.tags,
                sensitivity = memory.sensitivity,
                importance = memory.importance,
                scope = memory.metadata[META_SCOPE],
                userSyncChoice = existing.syncDecision,
                metadata = memory.metadata,
            ),
        )
        val updated = memory.copy(
            version = existing.version + 1,
            updatedAt = clock(),
            contentHash = canonicalHashOf(memory),
            syncDecision = policy.syncDecision,
            policyReason = policy.reason,
            redactedTitle = policy.redacted?.title,
            redactedContent = policy.redacted?.content,
            syncState = existing.syncState,
            origin = existing.origin,
            authority = existing.authority,
        )

        val vector = embedFor(updated)
        val stored = recordStore.get(RecordId.fromString(updated.memoryId))
            ?: throw EdgeError.MemoryNotFound(updated.memoryId)
        recordStore.upsert(
            MemoryRecordMapper.toRecord(updated, vector).copy(
                // Preserve record-layer facts the domain model does not carry.
                deletedAt = stored.deletedAt,
                lastSyncedVersion = stored.lastSyncedVersion,
                lastSyncedOperationId = stored.lastSyncedOperationId,
                lastSyncedContentHash = stored.lastSyncedContentHash,
            ),
        )
        applyDetectionOutcome(updated)
    }

    override suspend fun delete(memoryId: String) = withContext(dispatcher) {
        awaitStore()
        val id = recordIdOf(memoryId)
        val stored = recordStore.get(id) ?: return@withContext Unit
        // Idempotent: an already-tombstoned record keeps its single TOMBSTONE
        // operation; deleting twice must never mint a second version/identity.
        if (stored.tombstone) return@withContext Unit

        // Phase-12 tombstone semantics — never a physical delete:
        // version bump, tombstone envelope, and (for sync-eligible records)
        // PENDING so reconciliation R4 can see an unpropagated tombstone.
        val tombstoned = recordStore.softDelete(id)
        syncEngine.enqueueIfChanged(tombstoned)
        Unit
    }

    // ------------------------------------------------------------------
    // Read path
    // ------------------------------------------------------------------

    override suspend fun get(memoryId: String): Memory? = withContext(dispatcher) {
        awaitStore()
        storedMemory(memoryId)
    }

    override suspend fun list(): List<Memory> = withContext(dispatcher) {
        awaitStore()
        val out = mutableListOf<Memory>()
        var offset: String? = null
        while (true) {
            val page = recordStore.scroll(RecordQuery.allActive(limit = PAGE_LIMIT, offsetId = offset))
            page.records.map { MemoryRecordMapper.toMemory(it) }.forEach(out::add)
            if (page.nextOffsetId == null || page.records.isEmpty()) break
            offset = page.nextOffsetId
        }
        // Legacy MemoryDao.listAll() was ORDER BY updatedAt DESC; scroll order
        // is durable but not recency-ordered, so the repository preserves the
        // application-visible contract explicitly.
        out.sortedByDescending { it.updatedAt }
    }

    override suspend fun search(query: String, limit: Int): List<RetrievedMemory> =
        withContext(dispatcher) {
            val normalized = normalize(query)
            if (normalized.isEmpty()) {
                throw EdgeError.InvalidInput("search query is empty")
            }
            val vector = try {
                embeddingService.embed(normalized)
            } catch (e: EdgeError) {
                throw EdgeError.EmbeddingError(e.message ?: "query embedding failed", e)
            } catch (e: Exception) {
                throw EdgeError.EmbeddingError("query embedding failed: ${e.message}", e)
            }
            awaitStore()
            // Dense search inside the ACTIVE knowledge set; evidence comes
            // back complete (metadata travels on the same Qdrant point), so
            // there is no second store to join against.
            recordStore.search(
                RecordQuery.searchInTypes(
                    vector = vector,
                    recordTypes = MemoryRecordMapper.APP_MEMORY_RECORD_TYPES,
                    limit = limit.coerceAtLeast(1) * CANDIDATE_MULTIPLIER,
                ),
            )
                .map { MemoryRecordMapper.toMemory(it.record) to it.score }
                .map { (memory, score) -> RetrievedMemory(memory = memory, score = score) }
                .take(limit)
        }

    override suspend fun count(): Long = withContext(dispatcher) {
        awaitStore()
        recordStore.count(
            RecordQuery.Count(
                filter = com.example.EdgeMemo.core.record.RecordFilter.activeOnly(),
                recordTypes = MemoryRecordMapper.APP_MEMORY_RECORD_TYPES,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Persist one fresh memory and run change detection over the STORED
     * record. Detection always consumes the record re-read through `get`
     * because only retrieve round-trips the vector (search results are
     * payload-complete but vector-less by store contract) — detecting from a
     * vector-less copy would downgrade the point to payload-only.
     */
    private suspend fun persistNew(memory: Memory): Memory {
        awaitStore()
        val vector = embedFor(memory)
        recordStore.upsert(MemoryRecordMapper.toRecord(memory, vector))
        return applyDetectionOutcome(memory)
    }

    private suspend fun applyDetectionOutcome(memory: Memory): Memory {
        val stored = recordStore.get(RecordId.fromString(memory.memoryId))
            ?: throw EdgeError.LocalStorageError("record vanished after write: ${memory.memoryId}")
        val outcome = syncEngine.enqueueIfChanged(stored)
        return when (outcome) {
            is ChangeDetectionOutcome.Enqueued,
            is ChangeDetectionOutcome.AlreadyEnqueued,
            -> memory.copy(syncState = MemorySyncState.PENDING)

            else -> {
                // Policy-refused or cloud-origin: legacy semantics said the
                // row reverts to LOCAL and any withdrawn operation leaves it
                // un-syncable. Clear a stale PENDING/SYNCED stamp the same way
                // (state-only write at the same version; content identity is
                // envelope-excluded and therefore unchanged).
                if (stored.syncState != SyncState.LOCAL) {
                    recordStore.upsert(stored.copy(syncState = SyncState.LOCAL))
                }
                memory.copy(syncState = MemorySyncState.LOCAL)
            }
        }
    }

    private suspend fun storedMemory(memoryId: String): Memory? {
        val record = recordStore.get(recordIdOf(memoryId)) ?: return null
        if (record.tombstone) return null
        return MemoryRecordMapper.toMemory(record)
    }

    private suspend fun awaitStore() {
        recordStore.ensureReady(embeddingService.dimension)
        recordStore.ensureIndexes()
    }

    private fun recordIdOf(memoryId: String): RecordId =
        try {
            RecordId.fromString(memoryId)
        } catch (e: IllegalArgumentException) {
            throw EdgeError.InvalidInput("memory id is not a valid UUID: $memoryId")
        }

    private fun buildMemory(input: CreateMemoryInput, clock: Long): Memory {
        val title = normalize(input.title)
        val content = normalize(input.content)
        if (title.isEmpty() && content.isEmpty()) {
            throw EdgeError.InvalidInput("memory title and content are both empty")
        }
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
        val provisional = Memory(
            memoryId = idGenerator(),
            title = title,
            content = content,
            chunkId = input.chunkId,
            source = input.source,
            type = input.type,
            tags = input.tags,
            createdAt = clock,
            updatedAt = clock,
            origin = MemoryOrigin.LOCAL,
            syncDecision = policy.syncDecision,
            syncState = MemorySyncState.LOCAL,
            sensitivity = input.sensitivity,
            importance = input.importance,
            version = 1,
            contentHash = "", // replaced by the canonical hash below
            subjectKey = input.subjectKey,
            supersedes = null,
            tombstone = false,
            metadata = metadata,
            policyReason = policy.reason,
            redactedTitle = policy.redacted?.title,
            redactedContent = policy.redacted?.content,
        )
        return provisional.copy(contentHash = canonicalHashOf(provisional))
    }

    /**
     * The frozen Phase-12 content identity: canonical hash over the domain
     * payload (title/content/type/chunkId/sensitivity/importance). Envelope
     * sync metadata is excluded by construction, so stamping PENDING or a
     * watermark can never change it.
     */
    private fun canonicalHashOf(memory: Memory): String =
        com.example.EdgeMemo.core.sync.CanonicalContentHash.hash(
            MemoryRecordMapper.payloadOf(memory),
        )

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

    private fun normalize(text: String): String = text.trim().replace(whitespaceRegex, " ")

    private companion object {
        const val META_SCOPE = "scope"
        const val PAGE_LIMIT = 200
        const val CANDIDATE_MULTIPLIER = 4
    }
}
