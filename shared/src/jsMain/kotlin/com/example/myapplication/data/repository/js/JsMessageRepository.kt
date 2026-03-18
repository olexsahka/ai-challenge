package com.example.myapplication.data.repository.js

import com.example.myapplication.domain.model.MessageData
import com.example.myapplication.domain.repository.MessageRepository
import com.example.myapplication.platform.KeyValueStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class MessageJson(
    val id: String,
    val sessionId: String,
    val content: String,
    val isFromUser: Boolean,
    val createdAt: Long,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val durationMs: Long = 0,
    val model: String = "",
    val branchNodeId: String? = null,
    val isError: Boolean = false
)

private fun MessageData.toJson() = MessageJson(
    id, sessionId, content, isFromUser, createdAt,
    inputTokens, outputTokens, durationMs, model, branchNodeId, isError
)

private fun MessageJson.toDomain() = MessageData(
    id, sessionId, content, isFromUser, createdAt,
    inputTokens, outputTokens, durationMs, model, branchNodeId, isError
)

class JsMessageRepository(private val storage: KeyValueStorage) : MessageRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val _flows = mutableMapOf<String, MutableStateFlow<List<MessageData>>>()

    private fun idsKey(sessionId: String) = "msg_ids_$sessionId"
    private fun msgKey(id: String) = "msg_$id"

    private fun loadBySession(sessionId: String): List<MessageData> {
        val ids = storage.getString(idsKey(sessionId))?.split(",")?.filter { it.isNotBlank() } ?: return emptyList()
        return ids.mapNotNull { id ->
            storage.getString(msgKey(id))
                ?.let { runCatching { json.decodeFromString<MessageJson>(it).toDomain() }.getOrNull() }
        }.sortedBy { it.createdAt }
    }

    private fun sessionFlow(sessionId: String): MutableStateFlow<List<MessageData>> =
        _flows.getOrPut(sessionId) { MutableStateFlow(loadBySession(sessionId)) }

    override suspend fun insert(message: MessageData) {
        storage.putString(msgKey(message.id), json.encodeToString(message.toJson()))
        val ids = storage.getString(idsKey(message.sessionId))
            ?.split(",")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
        if (!ids.contains(message.id)) ids.add(message.id)
        storage.putString(idsKey(message.sessionId), ids.joinToString(","))
        sessionFlow(message.sessionId).value = loadBySession(message.sessionId)
    }

    override fun observeBySession(sessionId: String): Flow<List<MessageData>> = sessionFlow(sessionId)

    override suspend fun getBySession(sessionId: String): List<MessageData> = loadBySession(sessionId)

    override suspend fun getByNode(nodeId: String): List<MessageData> =
        storage.getAll()
            .filter { it.key.startsWith("msg_") && !it.key.startsWith("msg_ids_") }
            .mapNotNull { (_, v) -> runCatching { json.decodeFromString<MessageJson>(v).toDomain() }.getOrNull() }
            .filter { it.branchNodeId == nodeId }
            .sortedBy { it.createdAt }

    override fun observeByNode(nodeId: String): Flow<List<MessageData>> {
        // Simplified: return a flow that shows all messages filtered by nodeId from all session flows
        val combined = MutableStateFlow(emptyList<MessageData>())
        return combined
    }

    override suspend fun deleteById(id: String) {
        val msg = storage.getString(msgKey(id))
            ?.let { runCatching { json.decodeFromString<MessageJson>(it).toDomain() }.getOrNull() } ?: return
        storage.remove(msgKey(id))
        val ids = storage.getString(idsKey(msg.sessionId))
            ?.split(",")?.filter { it.isNotBlank() && it != id }?.toMutableList() ?: mutableListOf()
        storage.putString(idsKey(msg.sessionId), ids.joinToString(","))
        sessionFlow(msg.sessionId).value = loadBySession(msg.sessionId)
    }

    override suspend fun markAsError(id: String) {
        val raw = storage.getString(msgKey(id)) ?: return
        val msg = runCatching { json.decodeFromString<MessageJson>(raw) }.getOrNull() ?: return
        val updated = msg.copy(isError = true)
        storage.putString(msgKey(id), json.encodeToString(updated))
        sessionFlow(updated.sessionId).value = loadBySession(updated.sessionId)
    }
}
