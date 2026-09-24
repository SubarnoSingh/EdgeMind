package com.example.EdgeMemo.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ConflictDao {

    @Query("SELECT * FROM conflicts ORDER BY CASE WHEN state = 'UNRESOLVED' THEN 0 ELSE 1 END, detectedAt DESC")
    suspend fun listAll(): List<ConflictEntity>

    @Query("SELECT * FROM conflicts WHERE conflictId = :conflictId LIMIT 1")
    suspend fun getById(conflictId: String): ConflictEntity?

    @Query("SELECT COUNT(*) FROM conflicts WHERE state = 'UNRESOLVED'")
    suspend fun countUnresolved(): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(entity: ConflictEntity): Long

    @Update
    suspend fun update(entity: ConflictEntity)
}