package com.example.myapplication.data.repository.room

import com.example.myapplication.data.db.dao.SummaryDao
import com.example.myapplication.data.db.entity.SummaryEntity
import com.example.myapplication.domain.model.SummaryData
import com.example.myapplication.domain.repository.SummaryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSummaryRepository(private val dao: SummaryDao) : SummaryRepository {

    override suspend fun getBySession(sessionId: String): SummaryData? =
        dao.getBySession(sessionId)?.toDomain()

    override suspend fun upsert(summary: SummaryData) = dao.upsert(summary.toEntity())

    override fun observeBySession(sessionId: String): Flow<SummaryData?> =
        dao.observeBySession(sessionId).map { it?.toDomain() }

    override suspend fun deleteBySession(sessionId: String) = dao.deleteBySession(sessionId)
}

fun SummaryEntity.toDomain() = SummaryData(
    sessionId = sessionId,
    summary = summary,
    coveredMessageCount = coveredMessageCount,
    updatedAt = updatedAt
)

fun SummaryData.toEntity() = SummaryEntity(
    sessionId = sessionId,
    summary = summary,
    coveredMessageCount = coveredMessageCount,
    updatedAt = updatedAt
)
