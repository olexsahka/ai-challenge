package com.example.myapplication.data.repository.js

import com.example.myapplication.domain.model.FactData
import com.example.myapplication.domain.repository.FactRepository
import com.example.myapplication.platform.KeyValueStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class FactJson(
    val sessionId: String,
    val factKey: String,
    val factValue: String,
    val updatedAt: Long = 0L
)

private fun FactData.toJson() = FactJson(sessionId, factKey, factValue, updatedAt)
private fun FactJson.toDomain() = FactData(sessionId, factKey, factValue, updatedAt)

class JsFactRepository(private val storage: KeyValueStorage) : FactRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val flows = mutableMapOf<String, MutableStateFlow<List<FactData>>>()

    // Facts stored as a JSON array under one key per session
    private fun key(sessionId: String) = "facts_$sessionId"

    private fun load(sessionId: String): List<FactData> {
        val raw = storage.getString(key(sessionId)) ?: return emptyList()
        return runCatching { json.decodeFromString<List<FactJson>>(raw).map { it.toDomain() } }.getOrDefault(emptyList())
    }

    private fun sessionFlow(sessionId: String): MutableStateFlow<List<FactData>> =
        flows.getOrPut(sessionId) { MutableStateFlow(load(sessionId)) }

    override suspend fun getBySession(sessionId: String): List<FactData> = load(sessionId)

    override suspend fun deleteBySession(sessionId: String) {
        storage.remove(key(sessionId))
        flows[sessionId]?.value = emptyList()
    }

    override suspend fun upsertAll(facts: List<FactData>) {
        if (facts.isEmpty()) return
        val sessionId = facts.first().sessionId
        val existing = load(sessionId).associateBy { it.factKey }.toMutableMap()
        facts.forEach { existing[it.factKey] = it }
        val updated = existing.values.toList()
        storage.putString(key(sessionId), json.encodeToString(updated.map { it.toJson() }))
        sessionFlow(sessionId).value = updated
    }

    override fun observeBySession(sessionId: String): Flow<List<FactData>> = sessionFlow(sessionId)
}
