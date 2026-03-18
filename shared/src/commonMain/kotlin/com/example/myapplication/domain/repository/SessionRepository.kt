package com.example.myapplication.domain.repository

import com.example.myapplication.domain.model.Session
import com.example.myapplication.domain.model.SessionContextConfig
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    fun observeAll(): Flow<List<Session>>
    suspend fun getLatest(): Session?
    suspend fun getById(id: String): Session?
    suspend fun insert(session: Session)
    suspend fun updateContext(id: String, config: SessionContextConfig)
    suspend fun updateTitle(id: String, title: String)
    suspend fun countAssistantMessages(sessionId: String): Int
}
