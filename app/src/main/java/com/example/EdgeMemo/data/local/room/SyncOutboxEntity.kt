package com.example.EdgeMemo.data.local.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable sync outbox row (spec §29). `operationId` is the idempotency key and
 * is stable across every retry of the same logical operation.
 *
 * The spec's `payload` field is stored as [payloadTitle]/[payloadContent] so
 * inspection is deterministic and the privacy tests can assert exactly what
 * may leave the device. [lastError] holds only a [com.example.EdgeMemo.core.sync.SyncFailureKind]
 * classification id — never raw memory text.
 */
@Entity(
    tableName = "sync_outbox",
    indices = [Index("memoryId"), Index("state")],
)
data class SyncOutboxEntity(
    @PrimaryKey val operationId: String,
    @ColumnInfo val memoryId: String,
    @ColumnInfo val operationType: String,
    @ColumnInfo val payloadTitle: String,
    @ColumnInfo val payloadContent: String,
    @ColumnInfo val createdAt: Long,
    @ColumnInfo val attempts: Int,
    @ColumnInfo val state: String,
    @ColumnInfo val lastError: String?,
)