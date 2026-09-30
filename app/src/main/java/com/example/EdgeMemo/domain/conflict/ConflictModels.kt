package com.example.EdgeMemo.domain.conflict

/**
 * Persistent resolution lifecycle of a knowledge conflict. A resolved conflict
 * keeps both sides' evidence; almost nothing is ever deleted.
 */
enum class ConflictResolutionState {
    UNRESOLVED,
    RESOLVED_LOCAL,
    RESOLVED_CLOUD,
    RESOLVED_MERGED,
    DISMISSED,
}

data class Conflict(
    val conflictId: String,
    val subjectKey: String,
    val localMemoryId: String?,
    val incomingMemoryId: String?,
    val localTitle: String,
    val incomingTitle: String,
    val localContent: String,
    val incomingContent: String,
    val localVersion: Int?,
    val incomingVersion: Int?,
    val localContentHash: String?,
    val incomingContentHash: String,
    val localOrigin: String,
    val incomingOrigin: String,
    val localAuthority: String?,
    val incomingAuthority: String?,
    val detectedAt: Long,
    val reason: String,
    val state: ConflictResolutionState,
    val resolvedAt: Long? = null,
    val resolution: String? = null,
    /**
     * UI Phase 4 — the recorded side's tombstone state, carried verbatim from
     * the durable conflict evidence (12B.9 recorder schema). The workspace
     * shows these so a deleted side is never misread as merely "older".
     */
    val localTombstone: Boolean = false,
    val incomingTombstone: Boolean = false,
)

interface ConflictRepository {
    suspend fun list(): List<Conflict>

    suspend fun get(conflictId: String): Conflict?

    suspend fun countUnresolved(): Long
}