package com.example.EdgeMemo.data.local.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A persisted, unresolved-or-resolved knowledge conflict. The row retains BOTH
 * sides' evidence (content, version, hash, origin, authority) so nothing is
 * silently destroyed before or after resolution.
 */
@Entity(
    tableName = "conflicts",
    indices = [
        Index("state"),
        Index("subjectKey"),
    ],
)
data class ConflictEntity(
    @PrimaryKey @ColumnInfo val conflictId: String,
    @ColumnInfo val subjectKey: String,
    @ColumnInfo val localMemoryId: String?,
    @ColumnInfo val incomingMemoryId: String?,
    @ColumnInfo val localTitle: String,
    @ColumnInfo val incomingTitle: String,
    @ColumnInfo val localContent: String,
    @ColumnInfo val incomingContent: String,
    @ColumnInfo val localVersion: Int?,
    @ColumnInfo val incomingVersion: Int?,
    @ColumnInfo val localContentHash: String?,
    @ColumnInfo val incomingContentHash: String,
    @ColumnInfo val localOrigin: String,
    @ColumnInfo val incomingOrigin: String,
    @ColumnInfo val localAuthority: String?,
    @ColumnInfo val incomingAuthority: String?,
    @ColumnInfo val detectedAt: Long,
    @ColumnInfo val reason: String,
    @ColumnInfo val state: String,
    @ColumnInfo val resolvedAt: Long? = null,
    @ColumnInfo val resolution: String? = null,
)