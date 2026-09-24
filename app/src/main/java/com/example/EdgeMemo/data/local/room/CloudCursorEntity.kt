package com.example.EdgeMemo.data.local.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Durable incremental cloud-pull checkpoint (single row). */
@Entity(tableName = "cloud_pull_cursor")
data class CloudCursorEntity(
    @PrimaryKey @ColumnInfo val id: String,
    @ColumnInfo val cursor: String?,
    @ColumnInfo val updatedAt: Long,
)