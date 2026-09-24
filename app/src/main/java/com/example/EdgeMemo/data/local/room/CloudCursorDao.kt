package com.example.EdgeMemo.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CloudCursorDao {

    @Query("SELECT * FROM cloud_pull_cursor WHERE id = 'checkpoint' LIMIT 1")
    suspend fun get(): CloudCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: CloudCursorEntity)
}