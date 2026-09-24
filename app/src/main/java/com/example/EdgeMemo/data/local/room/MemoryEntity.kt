package com.example.EdgeMemo.data.local.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memories",
    indices = [
        Index("createdAt"),
        Index("type"),
        Index("tombstone"),
        Index("subjectKey"),
    ],
)
data class MemoryEntity(
    @PrimaryKey val memoryId: String,
    @ColumnInfo val title: String,
    @ColumnInfo val content: String,
    @ColumnInfo val chunkId: String?,
    @ColumnInfo val source: String,
    @ColumnInfo val type: String,
    @ColumnInfo val tags: List<String>,
    @ColumnInfo val createdAt: Long,
    @ColumnInfo val updatedAt: Long,
    @ColumnInfo val origin: String,
    @ColumnInfo val syncDecision: String,
    @ColumnInfo val syncState: String,
    @ColumnInfo val sensitivity: String,
    @ColumnInfo val importance: Int,
    @ColumnInfo val version: Int,
    @ColumnInfo val contentHash: String,
    @ColumnInfo val subjectKey: String?,
    @ColumnInfo val supersedes: String?,
    @ColumnInfo val tombstone: Boolean,
    @ColumnInfo val metadata: Map<String, String>,
    @ColumnInfo val policyReason: String?,
    @ColumnInfo val redactedTitle: String?,
    @ColumnInfo val redactedContent: String?,
    @ColumnInfo val authority: String? = null,
)