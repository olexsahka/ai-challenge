package com.example.myapplication.data.repository.room

import com.example.myapplication.data.db.dao.SessionDao
import com.example.myapplication.data.db.entity.SessionEntity
import com.example.myapplication.domain.model.Session
import com.example.myapplication.domain.model.SessionContextConfig
import com.example.myapplication.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSessionRepository(private val dao: SessionDao) : SessionRepository {

    override fun observeAll(): Flow<List<Session>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getLatest(): Session? = dao.getLatest()?.toDomain()

    override suspend fun getById(id: String): Session? = dao.getById(id)?.toDomain()

    override suspend fun insert(session: Session) = dao.insert(session.toEntity())

    override suspend fun updateContext(id: String, config: SessionContextConfig) =
        dao.updateContext(
            id,
            config.systemPrompt,
            config.model,
            config.temperature,
            config.compressionEnabled,
            config.compressionN,
            config.compressionM,
            config.memoryStrategy,
            config.slidingWindowN,
            config.stickyFactsN
        )

    override suspend fun updateTitle(id: String, title: String) = dao.updateTitle(id, title)

    override suspend fun countAssistantMessages(sessionId: String): Int =
        dao.countAssistantMessages(sessionId)
}

fun SessionEntity.toDomain() = Session(
    id = id,
    startedAt = startedAt,
    title = title,
    systemPrompt = systemPrompt,
    model = model,
    temperature = temperature,
    memoryStrategy = memoryStrategy,
    compressionEnabled = compressionEnabled,
    compressionN = compressionN,
    compressionM = compressionM,
    slidingWindowN = slidingWindowN,
    stickyFactsN = stickyFactsN
)

fun Session.toEntity() = SessionEntity(
    id = id,
    startedAt = startedAt,
    title = title,
    systemPrompt = systemPrompt,
    model = model,
    temperature = temperature,
    memoryStrategy = memoryStrategy,
    compressionEnabled = compressionEnabled,
    compressionN = compressionN,
    compressionM = compressionM,
    slidingWindowN = slidingWindowN,
    stickyFactsN = stickyFactsN
)
