package com.example.myapplication.data.repository.room

import com.example.myapplication.data.db.dao.FactDao
import com.example.myapplication.data.db.entity.FactEntity
import com.example.myapplication.domain.model.FactData
import com.example.myapplication.domain.repository.FactRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomFactRepository(private val dao: FactDao) : FactRepository {

    override suspend fun getBySession(sessionId: String): List<FactData> =
        dao.getBySession(sessionId).map { it.toDomain() }

    override suspend fun deleteBySession(sessionId: String) = dao.deleteBySession(sessionId)

    override suspend fun upsertAll(facts: List<FactData>) =
        dao.upsertAll(facts.map { it.toEntity() })

    override fun observeBySession(sessionId: String): Flow<List<FactData>> =
        dao.observeBySession(sessionId).map { list -> list.map { it.toDomain() } }
}

fun FactEntity.toDomain() = FactData(
    sessionId = sessionId,
    factKey = factKey,
    factValue = factValue,
    updatedAt = updatedAt
)

fun FactData.toEntity() = FactEntity(
    sessionId = sessionId,
    factKey = factKey,
    factValue = factValue,
    updatedAt = updatedAt
)
