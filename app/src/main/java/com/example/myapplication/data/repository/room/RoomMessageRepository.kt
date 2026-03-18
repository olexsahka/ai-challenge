package com.example.myapplication.data.repository.room

import com.example.myapplication.data.db.dao.MessageDao
import com.example.myapplication.data.db.entity.MessageEntity
import com.example.myapplication.domain.model.MessageData
import com.example.myapplication.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomMessageRepository(private val dao: MessageDao) : MessageRepository {

    override suspend fun insert(message: MessageData) = dao.insert(message.toEntity())

    override fun observeBySession(sessionId: String): Flow<List<MessageData>> =
        dao.observeBySession(sessionId).map { list -> list.map { it.toDomain() } }

    override suspend fun getBySession(sessionId: String): List<MessageData> =
        dao.getBySession(sessionId).map { it.toDomain() }

    override suspend fun getByNode(nodeId: String): List<MessageData> =
        dao.getByNode(nodeId).map { it.toDomain() }

    override fun observeByNode(nodeId: String): Flow<List<MessageData>> =
        dao.observeByNode(nodeId).map { list -> list.map { it.toDomain() } }

    override suspend fun deleteById(id: String) = dao.deleteById(id)

    override suspend fun markAsError(id: String) = dao.markAsError(id)
}

fun MessageEntity.toDomain() = MessageData(
    id = id,
    sessionId = sessionId,
    content = content,
    isFromUser = isFromUser,
    createdAt = createdAt,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    durationMs = durationMs,
    model = model,
    branchNodeId = branchNodeId,
    isError = isError
)

fun MessageData.toEntity() = MessageEntity(
    id = id,
    sessionId = sessionId,
    content = content,
    isFromUser = isFromUser,
    createdAt = createdAt,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    durationMs = durationMs,
    model = model,
    branchNodeId = branchNodeId,
    isError = isError
)
