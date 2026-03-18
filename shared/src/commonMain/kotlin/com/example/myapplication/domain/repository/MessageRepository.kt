package com.example.myapplication.domain.repository

import com.example.myapplication.domain.model.MessageData
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    suspend fun insert(message: MessageData)
    fun observeBySession(sessionId: String): Flow<List<MessageData>>
    suspend fun getBySession(sessionId: String): List<MessageData>
    suspend fun getByNode(nodeId: String): List<MessageData>
    fun observeByNode(nodeId: String): Flow<List<MessageData>>
    suspend fun deleteById(id: String)
    suspend fun markAsError(id: String)
}
