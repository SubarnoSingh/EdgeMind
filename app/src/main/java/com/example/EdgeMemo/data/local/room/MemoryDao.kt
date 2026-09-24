package com.example.EdgeMemo.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface MemoryDao {

    @Query("SELECT * FROM memories WHERE memoryId = :memoryId LIMIT 1")
    suspend fun getById(memoryId: String): MemoryEntity?

    /** Most recently updated active record for a subject (cloud knowledge matching). */
    @Query(
        "SELECT * FROM memories WHERE subjectKey = :subjectKey AND tombstone = 0 " +
            "ORDER BY updatedAt DESC LIMIT 1",
    )
    suspend fun findBySubjectKey(subjectKey: String): MemoryEntity?

    /** Insert-or-replace by primary key — used for cloud knowledge updates. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MemoryEntity)

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    suspend fun listAll(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE memoryId IN (:memoryIds) ORDER BY updatedAt DESC")
    suspend fun getByIds(memoryIds: List<String>): List<MemoryEntity>

    @Query(
        "SELECT * FROM memories WHERE tombstone = 0 AND (" +
            "LOWER(title) LIKE '%' || :term || '%' OR LOWER(content) LIKE '%' || :term || '%'" +
            ") ORDER BY updatedAt DESC LIMIT :limit",
    )
    suspend fun searchByKeyword(term: String, limit: Int): List<MemoryEntity>

    @Query("SELECT DISTINCT supersedes FROM memories WHERE supersedes IS NOT NULL")
    suspend fun supersededIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: MemoryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<MemoryEntity>)

    @Update
    suspend fun update(entity: MemoryEntity)

    @Query("DELETE FROM memories WHERE memoryId = :memoryId")
    suspend fun deleteById(memoryId: String)

    @Query("DELETE FROM memories WHERE memoryId IN (:memoryIds)")
    suspend fun deleteByIds(memoryIds: List<String>)

    @Query("UPDATE memories SET syncState = :state WHERE memoryId = :memoryId")
    suspend fun updateSyncState(memoryId: String, state: String)

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Long
}