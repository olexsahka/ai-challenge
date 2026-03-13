package com.example.myapplication.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeBySession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE branchNodeId = :nodeId ORDER BY createdAt ASC")
    fun observeByNode(nodeId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE branchNodeId = :nodeId ORDER BY createdAt ASC")
    suspend fun getByNode(nodeId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getBySession(sessionId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE messages SET isError = 1 WHERE id = :id")
    suspend fun markAsError(id: String)
}
