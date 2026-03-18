package com.example.myapplication.data.repository.js

import com.example.myapplication.domain.model.SummaryData
import com.example.myapplication.domain.repository.SummaryRepository
import com.example.myapplication.platform.KeyValueStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class SummaryJson(
    val sessionId: String,
    val summary: String,
    val coveredMessageCount: Int,
    val updatedAt: Long = 0L
)

private fun SummaryData.toJson() = SummaryJson(sessionId, summary, coveredMessageCount, updatedAt)
private fun SummaryJson.toDomain() = SummaryData(sessionId, summary, coveredMessageCount, updatedAt)

class JsSummaryRepository(private val storage: KeyValueStorage) : SummaryRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val flows = mutableMapOf<String, MutableStateFlow<SummaryData?>>()

    private fun key(sessionId: String) = "summary_$sessionId"

    private fun sessionFlow(sessionId: String): MutableStateFlow<SummaryData?> =
        flows.getOrPut(sessionId) {
            val initial = storage.getString(key(sessionId))
                ?.let { runCatching { json.decodeFromString<SummaryJson>(it).toDomain() }.getOrNull() }
            MutableStateFlow(initial)
        }

    override suspend fun getBySession(sessionId: String): SummaryData? =
        storage.getString(key(sessionId))
            ?.let { runCatching { json.decodeFromString<SummaryJson>(it).toDomain() }.getOrNull() }

    override suspend fun upsert(summary: SummaryData) {
        storage.putString(key(summary.sessionId), json.encodeToString(summary.toJson()))
        sessionFlow(summary.sessionId).value = summary
    }

    override fun observeBySession(sessionId: String): Flow<SummaryData?> = sessionFlow(sessionId)

    override suspend fun deleteBySession(sessionId: String) {
        storage.remove(key(sessionId))
        flows[sessionId]?.value = null
    }
}
