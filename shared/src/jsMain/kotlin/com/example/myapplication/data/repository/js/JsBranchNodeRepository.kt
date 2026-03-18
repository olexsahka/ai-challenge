package com.example.myapplication.data.repository.js

import com.example.myapplication.domain.model.BranchNode
import com.example.myapplication.domain.repository.BranchNodeRepository
import com.example.myapplication.platform.KeyValueStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class BranchNodeJson(
    val id: String,
    val sessionId: String,
    val parentId: String?,
    val label: String,
    val createdAt: Long = 0L
)

private fun BranchNode.toJson() = BranchNodeJson(id, sessionId, parentId, label, createdAt)
private fun BranchNodeJson.toDomain() = BranchNode(id, sessionId, parentId, label, createdAt)

class JsBranchNodeRepository(private val storage: KeyValueStorage) : BranchNodeRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val flows = mutableMapOf<String, MutableStateFlow<List<BranchNode>>>()

    private fun listKey(sessionId: String) = "branches_$sessionId"
    private fun nodeKey(id: String) = "branch_$id"

    private fun load(sessionId: String): List<BranchNode> {
        val ids = storage.getString(listKey(sessionId))?.split(",")?.filter { it.isNotBlank() } ?: return emptyList()
        return ids.mapNotNull { id ->
            storage.getString(nodeKey(id))
                ?.let { runCatching { json.decodeFromString<BranchNodeJson>(it).toDomain() }.getOrNull() }
        }.sortedBy { it.createdAt }
    }

    private fun sessionFlow(sessionId: String): MutableStateFlow<List<BranchNode>> =
        flows.getOrPut(sessionId) { MutableStateFlow(load(sessionId)) }

    override suspend fun getBySession(sessionId: String): List<BranchNode> = load(sessionId)

    override fun observeBySession(sessionId: String): Flow<List<BranchNode>> = sessionFlow(sessionId)

    override suspend fun insert(node: BranchNode) {
        storage.putString(nodeKey(node.id), json.encodeToString(node.toJson()))
        val ids = storage.getString(listKey(node.sessionId))
            ?.split(",")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
        if (!ids.contains(node.id)) ids.add(node.id)
        storage.putString(listKey(node.sessionId), ids.joinToString(","))
        sessionFlow(node.sessionId).value = load(node.sessionId)
    }

    override suspend fun getById(id: String): BranchNode? =
        storage.getString(nodeKey(id))
            ?.let { runCatching { json.decodeFromString<BranchNodeJson>(it).toDomain() }.getOrNull() }

    override suspend fun updateLabel(id: String, label: String) {
        val node = getById(id) ?: return
        val updated = node.copy(label = label)
        storage.putString(nodeKey(id), json.encodeToString(updated.toJson()))
        sessionFlow(node.sessionId).value = load(node.sessionId)
    }
}
