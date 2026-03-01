package com.example.myapplication.agent

import com.example.myapplication.data.api.AnthropicApi
import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.InputMessage
import com.example.myapplication.data.db.dao.MessageDao
import com.example.myapplication.data.db.dao.SessionDao
import com.example.myapplication.data.db.dao.SummaryDao
import com.example.myapplication.data.db.entity.MessageEntity
import com.example.myapplication.data.db.entity.SessionEntity
import com.example.myapplication.data.db.entity.SummaryEntity
import com.example.myapplication.domain.model.Message
import com.example.myapplication.domain.model.MessageMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class LLMAgent(
    private val api: AnthropicApi,
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val memory: AgentMemory,
    private val summaryDao: SummaryDao
) {
    private val titleFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    fun observeSessions(): Flow<List<SessionEntity>> = sessionDao.observeAll()

    fun observeMessages(sessionId: String): Flow<List<Message>> =
        messageDao.observeBySession(sessionId).map { entities ->
            entities.map { e ->
                Message(
                    id = e.id,
                    content = e.content,
                    isFromUser = e.isFromUser,
                    meta = if (!e.isFromUser && e.model.isNotEmpty()) {
                        MessageMeta(
                            inputTokens = e.inputTokens,
                            outputTokens = e.outputTokens,
                            durationMs = e.durationMs,
                            model = e.model
                        )
                    } else null
                )
            }
        }

    suspend fun getOrRestoreLastSession(): SessionEntity? = sessionDao.getLatest()

    suspend fun createSession(): SessionEntity {
        val now = System.currentTimeMillis()
        val session = SessionEntity(
            id = UUID.randomUUID().toString(),
            startedAt = now,
            title = titleFormat.format(Date(now))
        )
        sessionDao.insert(session)
        return session
    }

    suspend fun updateSessionContext(
        sessionId: String,
        systemPrompt: String,
        model: String,
        temperature: Float,
        compressionEnabled: Boolean,
        compressionN: Int,
        compressionM: Int
    ) {
        sessionDao.updateContext(sessionId, systemPrompt, model, temperature, compressionEnabled, compressionN, compressionM)
    }

    suspend fun getSession(sessionId: String): SessionEntity? = sessionDao.getById(sessionId)

    fun observeSummary(sessionId: String): Flow<com.example.myapplication.data.db.entity.SummaryEntity?> =
        summaryDao.observeBySession(sessionId)

    fun getMemories(): List<MemoryEntry> = memory.recallAll()

    fun forgetMemory(key: String) = memory.forget(key)

    fun forgetAllMemory() = memory.forgetAll()

    suspend fun sendMessage(sessionId: String, userText: String): Result<Message> {
        val session = sessionDao.getById(sessionId)
            ?: return Result.failure(Exception("Session not found"))

        val userMsg = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            content = userText,
            isFromUser = true,
            createdAt = System.currentTimeMillis()
        )
        messageDao.insert(userMsg)

        return try {
            val instructions = buildInstructions(session)
            val history = buildHistory(session)
            val request = ChatRequest(
                model = session.model,
                instructions = instructions.takeIf { it.isNotBlank() },
                input = history,
                temperature = if (session.temperature == 1.0f) null else session.temperature
            )
            val startMs = System.currentTimeMillis()
            val response = api.sendMessage(request)
            val durationMs = System.currentTimeMillis() - startMs

            val text = response.output
                .firstOrNull { it.type == "message" }
                ?.content
                ?.firstOrNull { it.type == "output_text" }
                ?.text
                ?: return Result.failure(Exception("Empty response"))

            val assistantEntity = MessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                content = text,
                isFromUser = false,
                createdAt = System.currentTimeMillis(),
                inputTokens = response.usage?.input_tokens ?: 0,
                outputTokens = response.usage?.output_tokens ?: 0,
                durationMs = durationMs,
                model = session.model
            )
            messageDao.insert(assistantEntity)

            Result.success(
                Message(
                    id = assistantEntity.id,
                    content = text,
                    isFromUser = false,
                    meta = MessageMeta(
                        inputTokens = assistantEntity.inputTokens,
                        outputTokens = assistantEntity.outputTokens,
                        durationMs = durationMs,
                        model = session.model
                    )
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildInstructions(session: SessionEntity): String {
        val parts = mutableListOf<String>()
        if (session.systemPrompt.isNotBlank()) parts.add(session.systemPrompt)
        val memCtx = memory.toContextString()
        if (memCtx.isNotBlank()) parts.add(memCtx)
        return parts.joinToString("\n\n")
    }

    private suspend fun buildHistory(session: SessionEntity): List<InputMessage> {
        val all = messageDao.observeBySession(session.id).first()
        if (!session.compressionEnabled || all.size <= session.compressionN) {
            return all.map { e ->
                InputMessage(role = if (e.isFromUser) "user" else "assistant", content = e.content)
            }
        }

        val recent = all.takeLast(session.compressionN)
        val older = all.dropLast(session.compressionN)

        val summary = getOrUpdateSummary(session, older)

        val result = mutableListOf<InputMessage>()
        result.add(InputMessage(role = "user", content = "[Summary of earlier conversation]\n$summary"))
        result.add(InputMessage(role = "assistant", content = "Understood, I have the context from the earlier conversation."))
        result.addAll(recent.map { e ->
            InputMessage(role = if (e.isFromUser) "user" else "assistant", content = e.content)
        })
        return result
    }

    private suspend fun getOrUpdateSummary(session: SessionEntity, olderMessages: List<MessageEntity>): String {
        val olderCount = olderMessages.size
        val existing = summaryDao.getBySession(session.id)

        if (existing != null && olderCount - existing.coveredMessageCount < session.compressionM) {
            return existing.summary
        }

        val historyText = olderMessages.joinToString("\n") { e ->
            val role = if (e.isFromUser) "User" else "Assistant"
            "$role: ${e.content}"
        }
        val summaryRequest = ChatRequest(
            model = session.model,
            instructions = "You are a conversation summarizer. Produce a concise summary of the conversation provided, capturing key facts, decisions, and context. Reply with only the summary text.",
            input = listOf(InputMessage(role = "user", content = "Summarize this conversation:\n\n$historyText")),
            temperature = null
        )
        val response = api.sendMessage(summaryRequest)
        val summary = response.output
            .firstOrNull { it.type == "message" }
            ?.content
            ?.firstOrNull { it.type == "output_text" }
            ?.text
            ?: "No summary available."

        summaryDao.upsert(
            SummaryEntity(
                sessionId = session.id,
                summary = summary,
                coveredMessageCount = olderCount
            )
        )
        return summary
    }
}
