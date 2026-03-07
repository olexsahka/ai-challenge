package com.example.myapplication.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.db.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT 1")
    suspend fun getLatest(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    @Query("UPDATE sessions SET systemPrompt = :systemPrompt, model = :model, temperature = :temperature, compressionEnabled = :compressionEnabled, compressionN = :compressionN, compressionM = :compressionM, memoryStrategy = :memoryStrategy, slidingWindowN = :slidingWindowN, stickyFactsN = :stickyFactsN WHERE id = :id")
    suspend fun updateContext(id: String, systemPrompt: String, model: String, temperature: Float, compressionEnabled: Boolean, compressionN: Int, compressionM: Int, memoryStrategy: String, slidingWindowN: Int, stickyFactsN: Int)

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId AND isFromUser = 0")
    suspend fun countAssistantMessages(sessionId: String): Int
}
