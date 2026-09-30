package com.example.EdgeMemo.data.conflict

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.record.LocalRecordStore
import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordId
import com.example.EdgeMemo.core.record.RecordQuery
import com.example.EdgeMemo.core.record.RecordType
import com.example.EdgeMemo.core.record.getString
import com.example.EdgeMemo.data.local.sync.QdrantConflictRecorder
import com.example.EdgeMemo.data.local.sync.QdrantConflictResolver
import com.example.EdgeMemo.domain.conflict.Conflict
import com.example.EdgeMemo.domain.conflict.ConflictRepository
import com.example.EdgeMemo.domain.conflict.ConflictResolutionState
import com.example.EdgeMemo.domain.conflict.ConflictResolver

/**
 * Phase 13.4 — the ACTIVE conflict store: the domain `ConflictRepository`
 * and `ConflictResolver` boundaries served entirely from Qdrant-native
 * conflict points (the durable evidence records 12B.9's
 * [QdrantConflictRecorder] writes and 12B.10's [QdrantConflictResolver]
 * resolves). No Room.
 *
 * The UI/use-case contracts (`ListConflictsUseCase`,
 * `CountUnresolvedConflictsUseCase`, `ResolveConflictUseCase`) are
 * unchanged: legacy ordering (unresolved first, then most recent detected)
 * and the keep-local / keep-cloud / dismiss semantics — including the
 * resolver's frozen crash-safe intent protocol, deterministic resolution
 * versions, follow-up operations, tombstone/resurrection guards and
 * immutability of resolved evidence — are the existing 12B implementations,
 * not reimplementations.
 *
 * Conflict ids are the deterministic UUID point ids; a resolved conflict
 * never changes state again (the resolver refuses re-resolution, matching
 * the legacy `require(state == UNRESOLVED)` contract).
 */
class QdrantConflictStore(
    private val recordStore: LocalRecordStore,
    private val resolver: QdrantConflictResolver,
) : ConflictRepository, ConflictResolver {

    // ------------------------------------------------------------------
    // ConflictRepository (read side for the UI)
    // ------------------------------------------------------------------

    override suspend fun list(): List<Conflict> = scrollAllConflicts()
        .map { QdrantConflictRecorder.caseOf(it).toDomainConflict() }
        .sortedWith(
            compareBy<Conflict> { it.state != ConflictResolutionState.UNRESOLVED }
                .thenByDescending { it.detectedAt },
        )

    override suspend fun get(conflictId: String): Conflict? =
        recordIdOrNull(conflictId)?.let { id ->
            resolver.find(id)?.let { QdrantConflictRecorder.caseOf(it).toDomainConflict() }
        }

    override suspend fun countUnresolved(): Long =
        scrollAllConflicts().count { QdrantConflictRecorder.caseOf(it).isUnresolved }.toLong()

    // ------------------------------------------------------------------
    // ConflictResolver (the explicit user actions)
    // ------------------------------------------------------------------

    override suspend fun keepLocal(conflictId: String, note: String?): Conflict {
        val id = requireExisting(conflictId)
        return resolvedToDomain(resolver.resolveLocal(id, note), conflictId)
    }

    override suspend fun keepCloud(conflictId: String, note: String?): Conflict {
        val id = requireExisting(conflictId)
        return resolvedToDomain(resolver.resolveCloud(id, note), conflictId)
    }

    override suspend fun dismiss(conflictId: String, note: String?): Conflict {
        val id = requireExisting(conflictId)
        return resolvedToDomain(resolver.dismiss(id, note), conflictId)
    }

    /** Honest domain error for a missing/unknown conflict id. */
    private suspend fun requireExisting(conflictId: String): RecordId {
        val id = requireRecordId(conflictId)
        if (resolver.find(id) == null) throw EdgeError.ConflictNotFound(conflictId)
        return id
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun scrollAllConflicts(): List<Record> {
        val out = ArrayList<Record>()
        var offset: String? = null
        while (true) {
            val page = recordStore.scroll(
                RecordQuery.Scroll(
                    limit = PAGE,
                    offsetId = offset,
                    recordTypes = setOf(RecordType.CONFLICT),
                ),
            )
            out.addAll(page.records)
            if (page.nextOffsetId == null || page.records.isEmpty()) break
            offset = page.nextOffsetId
        }
        return out
    }

    private fun requireRecordId(conflictId: String): RecordId =
        recordIdOrNull(conflictId) ?: throw EdgeError.ConflictNotFound(conflictId)

    private fun recordIdOrNull(conflictId: String): RecordId? =
        runCatching { RecordId.fromString(conflictId) }.getOrNull()

    private fun resolvedToDomain(
        resolution: com.example.EdgeMemo.data.local.sync.ConflictResolution,
        requestedId: String,
    ): Conflict {
        val case = QdrantConflictRecorder.caseOf(resolution.conflict)
        val domain = case.toDomainConflict()
        if (domain.conflictId != requestedId) {
            throw EdgeError.ConflictNotFound(requestedId)
        }
        return domain
    }

    private companion object {
        const val PAGE = 200
    }
}

/** Map the frozen Qdrant conflict evidence schema onto the domain model. */
private fun com.example.EdgeMemo.data.local.sync.ConflictCase.toDomainConflict(): Conflict =
    Conflict(
        conflictId = conflictId.uuid,
        subjectKey = subject,
        localMemoryId = localRecordId.uuid,
        incomingMemoryId = incomingRecordId.takeIf { it.isNotEmpty() },
        localTitle = localTitle,
        incomingTitle = incomingTitle,
        localContent = localContent,
        incomingContent = incomingContent,
        localVersion = localVersion,
        incomingVersion = incomingVersion,
        localContentHash = localContentHash.takeIf { it.isNotEmpty() },
        incomingContentHash = incomingContentHash,
        localOrigin = localOrigin,
        incomingOrigin = incomingOrigin,
        localAuthority = localAuthority,
        incomingAuthority = incomingAuthority,
        detectedAt = detectedAt,
        reason = reason,
        state = ConflictResolutionState.entries.firstOrNull { it.name == state }
            ?: ConflictResolutionState.UNRESOLVED,
        resolvedAt = resolvedAt,
        resolution = resolution,
        // UI Phase 4 — surface the recorded tombstone state (real evidence)
        // so the workspace never renders a deleted side as merely "older".
        localTombstone = localTombstone,
        incomingTombstone = incomingTombstone,
    )
