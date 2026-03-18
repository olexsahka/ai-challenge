package com.example.myapplication.domain.repository

import com.example.myapplication.domain.model.BranchNode
import kotlinx.coroutines.flow.Flow

interface BranchNodeRepository {
    suspend fun getBySession(sessionId: String): List<BranchNode>
    fun observeBySession(sessionId: String): Flow<List<BranchNode>>
    suspend fun insert(node: BranchNode)
    suspend fun getById(id: String): BranchNode?
    suspend fun updateLabel(id: String, label: String)
}
