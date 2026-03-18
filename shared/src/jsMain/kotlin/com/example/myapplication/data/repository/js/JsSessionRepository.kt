package com.example.myapplication.data.repository.js

import com.example.myapplication.domain.model.Session
import com.example.myapplication.domain.model.SessionContextConfig
import com.example.myapplication.domain.repository.SessionRepository
import com.example.myapplication.platform.KeyValueStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

@Serializable
private data class SessionJson(
    val id: String,
    val startedAt: Long,
    val title: String,
    val systemPrompt: String = "",
    val model: String = "gpt-4o-mini",
    val temperature: Float = 1.0f,
    val memoryStrategy: String = "FULL",
    val compressionEnabled: Boolean = false,
    val compressionN: Int = 5,
    val compressionM: Int = 6,
    val slidingWindowN: Int = 5,
    val stickyFactsN: Int = 5
)

private fun Session.toJson() = SessionJson(
    id, startedAt, title, systemPrompt, model, temperature,
    memoryStrategy, compressionEnabled, compressionN, compressionM, slidingWindowN, stickyFactsN
)

private fun SessionJson.toDomain() = Session(
    id, startedAt, title, systemPrompt, model, temperature,
    memoryStrategy, compressionEnabled, compressionN, compressionM, slidingWindowN, stickyFactsN
)

class JsSessionRepository(private val storage: KeyValueStorage) : SessionRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val KEY_IDS = "session_ids"
    private val _flow = MutableStateFlow<List<Session>>(emptyList())

    init {
        _flow.value = loadAll()
    }

    private fun sessionKey(id: String) = "session_$id"

    private fun loadAll(): List<Session> {
        val ids = storage.getString(KEY_IDS)?.split(",")?.filter { it.isNotBlank() } ?: return emptyList()
        return ids.mapNotNull { id ->
            storage.getString(sessionKey(id))
                ?.let { runCatching { json.decodeFromString<SessionJson>(it).toDomain() }.getOrNull() }
        }.sortedByDescending { it.startedAt }
    }

    private fun save(session: Session) {
        storage.putString(sessionKey(session.id), json.encodeToString(session.toJson()))
        val ids = storage.getString(KEY_IDS)?.split(",")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
        if (!ids.contains(session.id)) ids.add(session.id)
        storage.putString(KEY_IDS, ids.joinToString(","))
        _flow.value = loadAll()
    }

    override fun observeAll(): Flow<List<Session>> = _flow

    override suspend fun getLatest(): Session? = loadAll().firstOrNull()

    override suspend fun getById(id: String): Session? =
        storage.getString(sessionKey(id))
            ?.let { runCatching { json.decodeFromString<SessionJson>(it).toDomain() }.getOrNull() }

    override suspend fun insert(session: Session) = save(session)

    override suspend fun updateContext(id: String, config: SessionContextConfig) {
        val session = getById(id) ?: return
        save(session.copy(
            systemPrompt = config.systemPrompt,
            model = config.model,
            temperature = config.temperature,
            compressionEnabled = config.compressionEnabled,
            compressionN = config.compressionN,
            compressionM = config.compressionM,
            memoryStrategy = config.memoryStrategy,
            slidingWindowN = config.slidingWindowN,
            stickyFactsN = config.stickyFactsN
        ))
    }

    override suspend fun updateTitle(id: String, title: String) {
        val session = getById(id) ?: return
        save(session.copy(title = title))
    }

    override suspend fun countAssistantMessages(sessionId: String): Int = 0
}
