package com.example.myapplication.data.repository.room

import com.example.myapplication.data.db.dao.BranchNodeDao
import com.example.myapplication.data.db.entity.BranchNodeEntity
import com.example.myapplication.domain.model.BranchNode
import com.example.myapplication.domain.repository.BranchNodeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomBranchNodeRepository(private val dao: BranchNodeDao) : BranchNodeRepository {

    override suspend fun getBySession(sessionId: String): List<BranchNode> =
        dao.getBySession(sessionId).map { it.toDomain() }

    override fun observeBySession(sessionId: String): Flow<List<BranchNode>> =
        dao.observeBySession(sessionId).map { list -> list.map { it.toDomain() } }

    override suspend fun insert(node: BranchNode) = dao.insert(node.toEntity())

    override suspend fun getById(id: String): BranchNode? = dao.getById(id)?.toDomain()

    override suspend fun updateLabel(id: String, label: String) = dao.updateLabel(id, label)
}

fun BranchNodeEntity.toDomain() = BranchNode(
    id = id,
    sessionId = sessionId,
    parentId = parentId,
    label = label,
    createdAt = createdAt
)

fun BranchNode.toEntity() = BranchNodeEntity(
    id = id,
    sessionId = sessionId,
    parentId = parentId,
    label = label,
    createdAt = createdAt
)
