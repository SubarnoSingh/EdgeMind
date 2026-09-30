package com.example.EdgeMemo.data.sync

import com.example.EdgeMemo.core.record.LocalRecordStore
import com.example.EdgeMemo.core.sync.QdrantSyncEngine
import com.example.EdgeMemo.core.sync.SyncOperationStore
import com.example.EdgeMemo.data.local.sync.QdrantConflictResolver

/**
 * The single Qdrant-native sync execution context the worker drives.
 * One shard handle, one engine, one conflict resolver — constructed lazily
 * by the app container so WorkManager-initiated processes and Activity-
 * initiated code never own conflicting shard handles (12A §20 single-writer).
 *
 * Phase 13.2: the application memory repository shares THIS runtime's store
 * and engine — authoring and sync are one system over one shard, and the
 * operation store is exposed (read-only use) for honest sync-status reporting.
 */
class QdrantSyncRuntime(
    val recordStore: LocalRecordStore,
    val engine: QdrantSyncEngine,
    val conflicts: QdrantConflictResolver,
    val dimension: Int,
    val operations: SyncOperationStore? = null,
)
