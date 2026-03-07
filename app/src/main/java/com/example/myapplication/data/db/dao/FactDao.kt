package com.example.myapplication.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.db.entity.FactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(facts: List<FactEntity>)

    @Query("SELECT * FROM facts WHERE sessionId = :sessionId ORDER BY factKey ASC")
    suspend fun getBySession(sessionId: String): List<FactEntity>

    @Query("SELECT * FROM facts WHERE sessionId = :sessionId ORDER BY factKey ASC")
    fun observeBySession(sessionId: String): Flow<List<FactEntity>>

    @Query("DELETE FROM facts WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
