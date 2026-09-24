package com.example.EdgeMemo.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncOutboxDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(entity: SyncOutboxEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnoreAll(entities: List<SyncOutboxEntity>)

    /** Latest-wins payload refresh; never clobbers an in-flight operation. */
    @Query(
        "UPDATE sync_outbox SET payloadTitle = :title, payloadContent = :content, " +
            "state = 'PENDING', lastError = NULL WHERE operationId = :operationId AND state <> 'IN_FLIGHT'",
    )
    suspend fun refreshPayload(operationId: String, title: String, content: String): Int

    @Query(
        "SELECT * FROM sync_outbox WHERE state IN ('PENDING', 'FAILED') " +
            "ORDER BY createdAt ASC LIMIT :limit",
    )
    suspend fun selectRetryable(limit: Int): List<SyncOutboxEntity>

    /**
     * Crash recovery: any row still `IN_FLIGHT` when a worker starts belongs to
     * a worker that died. Reset it to retryable so a restarted process re-applies
     * the (idempotent) operation instead of losing it forever.
     */
    @Query("UPDATE sync_outbox SET state = 'FAILED', lastError = 'NETWORK' WHERE state = 'IN_FLIGHT'")
    suspend fun recoverStaleInFlight(): Int

    /** Single-winner claim: only one caller may move a row out of PENDING/FAILED. */
    @Query(
        "UPDATE sync_outbox SET state = 'IN_FLIGHT', attempts = attempts + 1 " +
            "WHERE operationId = :operationId AND state IN ('PENDING', 'FAILED')",
    )
    suspend fun claim(operationId: String): Int

    @Query(
        "UPDATE sync_outbox SET state = 'ACKED', lastError = NULL " +
            "WHERE operationId = :operationId AND state IN ('IN_FLIGHT', 'PENDING', 'FAILED')",
    )
    suspend fun markAcknowledged(operationId: String): Int

    @Query(
        "UPDATE sync_outbox SET state = :toState, lastError = :kind " +
            "WHERE operationId = :operationId AND state IN ('IN_FLIGHT', 'PENDING', 'FAILED')",
    )
    suspend fun markState(operationId: String, toState: String, kind: String?): Int

    @Query("SELECT * FROM sync_outbox WHERE operationId = :operationId LIMIT 1")
    suspend fun getById(operationId: String): SyncOutboxEntity?

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE state = :state")
    suspend fun countByState(state: String): Long

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun countAll(): Long

    /** A local delete cancels the memory's pending-but-unacknowledged ops. */
    @Query("DELETE FROM sync_outbox WHERE memoryId = :memoryId")
    suspend fun deleteByMemoryId(memoryId: String)
}