package com.example.EdgeMemo.domain.cloud

import com.example.EdgeMemo.core.common.EdgeError

/** Outcome of one pull for honest UI reporting — every field is real. */
data class CloudPullResult(
    val applied: Int = 0,
    val duplicates: Int = 0,
    val conflicts: Int = 0,
    val tombstoned: Int = 0,
    val noChange: Boolean = true,
    val cursor: String? = null,
) {
    val changed: Boolean
        get() = !noChange
}

/**
 * Orchestrates one incremental cloud → edge pass:
 * load checkpoint → pull batch → classify each item → apply accepted knowledge or
 * persist a conflict → save checkpoint. Network work happens only in
 * [CloudKnowledgeRemoteDataSource.pullKnowledge]; no network call ever runs
 * inside a Room transaction here.
 */
interface CloudKnowledgeIngestor {
    suspend fun pullAndApply(): CloudPullResult
}

/**
 * Applies an accepted cloud item so it is available to offline retrieval:
 * embed (existing EmbeddingService) → Qdrant Edge upsert (id = memoryId, one
 * point per identity) → Room metadata in a transaction. Cloud knowledge never
 * enters the sync outbox and is never pushed back — it arrived from the cloud.
 */
interface CloudKnowledgeWriter {
    suspend fun applyNew(item: CloudKnowledgeItem): com.example.EdgeMemo.core.model.Memory

    suspend fun applyUpdate(item: CloudKnowledgeItem, local: com.example.EdgeMemo.core.model.Memory): com.example.EdgeMemo.core.model.Memory

    /** Superseding item: the new record replaces the old in active retrieval. */
    suspend fun applySupersede(item: CloudKnowledgeItem): com.example.EdgeMemo.core.model.Memory

    /** Apply a cloud deletion signal to a local cloud/synced record. */
    suspend fun applyTombstone(item: CloudKnowledgeItem, local: com.example.EdgeMemo.core.model.Memory)

    suspend fun upsertVectorAndRoom(memory: com.example.EdgeMemo.core.model.Memory)
}