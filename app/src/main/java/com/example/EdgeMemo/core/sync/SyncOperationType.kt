package com.example.EdgeMemo.core.sync

/** The versioned intent represented by a Qdrant-native sync operation. */
enum class SyncOperationType {
    UPSERT,
    TOMBSTONE,
}
