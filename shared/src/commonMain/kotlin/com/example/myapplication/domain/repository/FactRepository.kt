package com.example.myapplication.domain.repository

import com.example.myapplication.domain.model.FactData
import kotlinx.coroutines.flow.Flow

interface FactRepository {
    suspend fun getBySession(sessionId: String): List<FactData>
    suspend fun deleteBySession(sessionId: String)
    suspend fun upsertAll(facts: List<FactData>)
    fun observeBySession(sessionId: String): Flow<List<FactData>>
}
