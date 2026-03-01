package com.example.myapplication.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.db.entity.SummaryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: SummaryEntity)

    @Query("SELECT * FROM summaries WHERE sessionId = :sessionId")
    suspend fun getBySession(sessionId: String): SummaryEntity?

    @Query("SELECT * FROM summaries WHERE sessionId = :sessionId")
    fun observeBySession(sessionId: String): Flow<SummaryEntity?>

    @Query("DELETE FROM summaries WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
