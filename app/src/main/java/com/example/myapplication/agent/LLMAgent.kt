package com.example.myapplication.agent

import com.example.myapplication.data.api.AnthropicApi
import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.InputMessage
import com.example.myapplication.data.db.dao.BranchNodeDao
import com.example.myapplication.data.db.dao.FactDao
import com.example.myapplication.data.db.dao.MessageDao
import com.example.myapplication.data.db.dao.SessionDao
import com.example.myapplication.data.db.dao.SummaryDao
import com.example.myapplication.data.repository.UserProfileRepository
import com.example.myapplication.data.db.entity.BranchNodeEntity
import com.example.myapplication.data.db.entity.FactEntity
import com.example.myapplication.data.db.entity.MessageEntity
import com.example.myapplication.data.db.entity.MemoryStrategy
import com.example.myapplication.data.db.entity.SessionEntity
import com.example.myapplication.data.db.entity.SummaryEntity
import com.example.myapplication.domain.model.Message
import com.example.myapplication.domain.model.MessageMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class LLMAgent(
    private val api: AnthropicApi,
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val memory: AgentMemory,
    private val summaryDao: SummaryDao,
    private val factDao: FactDao,
    private val branchNodeDao: BranchNodeDao,
    private val userProfileRepository: UserProfileRepository
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
        compressionM: Int,
        memoryStrategy: String,
        slidingWindowN: Int,
        stickyFactsN: Int
    ) {
        sessionDao.updateContext(sessionId, systemPrompt, model, temperature, compressionEnabled, compressionN, compressionM, memoryStrategy, slidingWindowN, stickyFactsN)
    }

    fun observeFacts(sessionId: String): Flow<List<FactEntity>> =
        factDao.observeBySession(sessionId)

    fun observeBranchNodes(sessionId: String): Flow<List<BranchNodeEntity>> =
        branchNodeDao.observeBySession(sessionId)

    suspend fun getOrCreateRootNode(sessionId: String): BranchNodeEntity {
        val existing = branchNodeDao.getBySession(sessionId).firstOrNull()
        if (existing != null) return existing
        val root = BranchNodeEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            parentId = null,
            label = "Root"
        )
        branchNodeDao.insert(root)
        return root
    }

    suspend fun forkNode(sessionId: String, fromNodeId: String, label: String): BranchNodeEntity {
        val allNodes = branchNodeDao.getBySession(sessionId)
        val siblingCount = allNodes.count { it.parentId == fromNodeId }
        val newLabel = if (label.isBlank()) "Branch ${siblingCount + 1}" else label
        val node = BranchNodeEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            parentId = fromNodeId,
            label = newLabel
        )
        branchNodeDao.insert(node)
        return node
    }

    suspend fun renameNode(nodeId: String, label: String) {
        branchNodeDao.updateLabel(nodeId, label)
    }

    suspend fun sendMessageToNode(sessionId: String, nodeId: String, userText: String): Result<Message> {
        val session = sessionDao.getById(sessionId)
            ?: return Result.failure(Exception("Session not found"))

        val existingMessages = messageDao.getByNode(nodeId)
        val isFirstMessage = existingMessages.isEmpty()

        val userMsg = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            content = userText,
            isFromUser = true,
            createdAt = System.currentTimeMillis(),
            branchNodeId = nodeId
        )
        messageDao.insert(userMsg)

        if (isFirstMessage) {
            val autoLabel = userText.split(Regex("(?<=[.!?])\\s+")).firstOrNull()
                ?.trim()?.take(40)
                ?: userText.take(40)
            branchNodeDao.updateLabel(nodeId, autoLabel)
        }

        return try {
            val instructions = buildInstructions(session)
            val history = buildBranchHistory(sessionId, nodeId)
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
                model = session.model,
                branchNodeId = nodeId
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

    fun observeNodeMessages(sessionId: String, nodeId: String): Flow<List<Message>> {
        // Collect ancestor chain node IDs (root first, current last)
        return branchNodeDao.observeBySession(sessionId).flatMapLatest { allNodes ->
            val nodeMap = allNodes.associateBy { it.id }
            val chain = mutableListOf<String>()
            var cursor: String? = nodeId
            while (cursor != null) {
                chain.add(0, cursor)
                cursor = nodeMap[cursor]?.parentId
            }
            // Observe each node's messages as separate flows, combine them in order
            if (chain.isEmpty()) {
                flowOf(emptyList())
            } else {
                val flows = chain.map { nId -> messageDao.observeByNode(nId) }
                combine(flows) { arrays -> arrays.flatMap { it.toList() } }
                    .map { entities ->
                        entities.map { e ->
                            Message(
                                id = e.id,
                                content = e.content,
                                isFromUser = e.isFromUser,
                                meta = if (!e.isFromUser && e.model.isNotEmpty()) MessageMeta(
                                    inputTokens = e.inputTokens,
                                    outputTokens = e.outputTokens,
                                    durationMs = e.durationMs,
                                    model = e.model
                                ) else null
                            )
                        }
                    }
            }
        }
    }

    private suspend fun buildBranchHistory(sessionId: String, nodeId: String): List<InputMessage> {
        val allNodes = branchNodeDao.getBySession(sessionId).associateBy { it.id }
        // Walk ancestor chain from current node up to root
        val chain = mutableListOf<String>()
        var cursor: String? = nodeId
        while (cursor != null) {
            chain.add(0, cursor)
            cursor = allNodes[cursor]?.parentId
        }
        val result = mutableListOf<InputMessage>()
        for (nId in chain) {
            val msgs = messageDao.getByNode(nId)
            result.addAll(msgs.map { e ->
                InputMessage(role = if (e.isFromUser) "user" else "assistant", content = e.content)
            })
        }
        return result
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

            if (sessionDao.countAssistantMessages(sessionId) == 1) {
                val autoTitle = text.split(Regex("(?<=[.!?])\\s+")).firstOrNull()
                    ?.trim()?.take(50)
                    ?: text.take(50)
                sessionDao.updateTitle(sessionId, autoTitle)
            }

            val strategy = runCatching { MemoryStrategy.valueOf(session.memoryStrategy) }.getOrDefault(MemoryStrategy.FULL)
            if (strategy == MemoryStrategy.STICKY_FACTS) {
                updateFacts(session, userText, text)
            }

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
        val profileCtx = userProfileRepository.toContextString()
        if (profileCtx.isNotBlank()) parts.add(profileCtx)
        return parts.joinToString("\n\n")
    }

    private suspend fun buildHistory(session: SessionEntity): List<InputMessage> {
        val all = messageDao.observeBySession(session.id).first()

        val strategy = runCatching { MemoryStrategy.valueOf(session.memoryStrategy) }.getOrDefault(MemoryStrategy.FULL)
        if (strategy == MemoryStrategy.SLIDING_WINDOW) {
            return all.takeLast(session.slidingWindowN).map { e ->
                InputMessage(role = if (e.isFromUser) "user" else "assistant", content = e.content)
            }
        }
        if (strategy == MemoryStrategy.STICKY_FACTS) {
            val facts = factDao.getBySession(session.id)
            val result = mutableListOf<InputMessage>()
            if (facts.isNotEmpty()) {
                val factsText = facts.joinToString("\n") { "- ${it.factKey}: ${it.factValue}" }
                result.add(InputMessage(role = "user", content = "[Known facts about this conversation]\n$factsText"))
                result.add(InputMessage(role = "assistant", content = "Understood, I have the known facts."))
            }
            result.addAll(all.takeLast(session.stickyFactsN).map { e ->
                InputMessage(role = if (e.isFromUser) "user" else "assistant", content = e.content)
            })
            return result
        }

        if (strategy != MemoryStrategy.COMPRESSION || all.size <= session.compressionN) {
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

    private suspend fun updateFacts(session: SessionEntity, userText: String, assistantText: String) {
        val existing = factDao.getBySession(session.id)
        val existingFactsBlock = if (existing.isNotEmpty())
            "Current facts:\n" + existing.joinToString("\n") { "- ${it.factKey}: ${it.factValue}" }
        else
            "No facts stored yet."

        val prompt = """You are a fact extractor. Given the existing facts and the latest exchange, return an updated list of key facts.

$existingFactsBlock

Latest exchange:
User: $userText
Assistant: $assistantText

Return ONLY a list of facts in this exact format, one per line:
key: value

Extract facts about: goals, constraints, preferences, decisions, agreements, names, dates, or any important information. Keep existing facts that are still valid. Update or remove facts that have changed."""

        val request = ChatRequest(
            model = session.model,
            instructions = null,
            input = listOf(InputMessage(role = "user", content = prompt)),
            temperature = null
        )
        val response = runCatching { api.sendMessage(request) }.getOrNull() ?: return
        val raw = response.output
            .firstOrNull { it.type == "message" }
            ?.content
            ?.firstOrNull { it.type == "output_text" }
            ?.text ?: return

        val newFacts = raw.lines()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim().trimStart('-').trim()
                    val value = line.substring(idx + 1).trim()
                    if (key.isNotBlank() && value.isNotBlank()) FactEntity(
                        sessionId = session.id,
                        factKey = key,
                        factValue = value
                    ) else null
                } else null
            }

        if (newFacts.isNotEmpty()) {
            factDao.deleteBySession(session.id)
            factDao.upsertAll(newFacts)
        }
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
