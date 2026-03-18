package com.example.myapplication.domain.repository

import com.example.myapplication.domain.model.SummaryData
import kotlinx.coroutines.flow.Flow

interface SummaryRepository {
    suspend fun getBySession(sessionId: String): SummaryData?
    suspend fun upsert(summary: SummaryData)
    fun observeBySession(sessionId: String): Flow<SummaryData?>
    suspend fun deleteBySession(sessionId: String)
}
