package com.example.myapplication

import com.example.myapplication.agent.AgentMemory
import com.example.myapplication.data.db.dao.BranchNodeDao
import com.example.myapplication.data.db.dao.FactDao
import com.example.myapplication.data.db.dao.MessageDao
import com.example.myapplication.data.db.dao.SessionDao
import com.example.myapplication.data.db.dao.SummaryDao
import com.example.myapplication.data.db.entity.BranchNodeEntity
import com.example.myapplication.data.db.entity.FactEntity
import com.example.myapplication.data.db.entity.MemoryStrategy
import com.example.myapplication.data.db.entity.MessageEntity
import com.example.myapplication.data.db.entity.SessionEntity
import com.example.myapplication.data.db.entity.SummaryEntity
import com.example.myapplication.data.repository.UserProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Общая тестовая инфраструктура для LLMAgent тестов.
 * Фаза 0 — используется в BuildHistoryTest, BuildInstructionsTest,
 * SendMessageTest, BuildBranchHistoryTest.
 */

// ---------------------------------------------------------------------------
// Fake DAO implementations (in-memory, no Room/Android)
// ---------------------------------------------------------------------------

class FakeSessionDao : SessionDao {
    val sessions = mutableMapOf<String, SessionEntity>()
    var assistantMessageCount = 0

    override fun observeAll(): Flow<List<SessionEntity>> =
        flowOf(sessions.values.sortedByDescending { it.startedAt })

    override suspend fun getLatest(): SessionEntity? =
        sessions.values.maxByOrNull { it.startedAt }

    override suspend fun getById(id: String): SessionEntity? = sessions[id]

    override suspend fun insert(session: SessionEntity) {
        sessions[session.id] = session
    }

    override suspend fun updateContext(
        id: String, systemPrompt: String, model: String, temperature: Float,
        compressionEnabled: Boolean, compressionN: Int, compressionM: Int,
        memoryStrategy: String, slidingWindowN: Int, stickyFactsN: Int
    ) {
        sessions[id]?.let {
            sessions[id] = it.copy(
                systemPrompt = systemPrompt, model = model, temperature = temperature,
                compressionEnabled = compressionEnabled, compressionN = compressionN,
                compressionM = compressionM, memoryStrategy = memoryStrategy,
                slidingWindowN = slidingWindowN, stickyFactsN = stickyFactsN
            )
        }
    }

    override suspend fun updateTitle(id: String, title: String) {
        sessions[id]?.let { sessions[id] = it.copy(title = title) }
    }

    override suspend fun countAssistantMessages(sessionId: String): Int = assistantMessageCount
}

class FakeMessageDao : MessageDao {
    val messages = mutableListOf<MessageEntity>()

    override suspend fun insert(message: MessageEntity) {
        messages.add(message)
    }

    override fun observeBySession(sessionId: String): Flow<List<MessageEntity>> =
        flowOf(
            messages.filter { it.sessionId == sessionId && it.branchNodeId == null }
                .sortedBy { it.createdAt }
        )

    override suspend fun getByNode(nodeId: String): List<MessageEntity> =
        messages.filter { it.branchNodeId == nodeId }.sortedBy { it.createdAt }

    override fun observeByNode(nodeId: String): Flow<List<MessageEntity>> =
        flowOf(messages.filter { it.branchNodeId == nodeId }.sortedBy { it.createdAt })
}

class FakeSummaryDao : SummaryDao {
    val summaries = mutableMapOf<String, SummaryEntity>()

    override suspend fun getBySession(sessionId: String): SummaryEntity? = summaries[sessionId]

    override suspend fun upsert(summary: SummaryEntity) {
        summaries[summary.sessionId] = summary
    }

    override fun observeBySession(sessionId: String): Flow<SummaryEntity?> =
        flowOf(summaries[sessionId])

    override suspend fun deleteBySession(sessionId: String) {
        summaries.remove(sessionId)
    }
}

class FakeFactDao : FactDao {
    val facts = mutableMapOf<String, MutableList<FactEntity>>()

    override suspend fun getBySession(sessionId: String): List<FactEntity> =
        facts[sessionId]?.sortedBy { it.factKey } ?: emptyList()

    override suspend fun deleteBySession(sessionId: String) {
        facts.remove(sessionId)
    }

    override suspend fun upsertAll(entities: List<FactEntity>) {
        entities.forEach { fact ->
            val list = facts.getOrPut(fact.sessionId) { mutableListOf() }
            list.removeIf { it.factKey == fact.factKey }
            list.add(fact)
        }
    }

    override fun observeBySession(sessionId: String): Flow<List<FactEntity>> =
        flowOf(facts[sessionId] ?: emptyList())
}

class FakeBranchNodeDao : BranchNodeDao {
    val nodes = mutableListOf<BranchNodeEntity>()

    override suspend fun getBySession(sessionId: String): List<BranchNodeEntity> =
        nodes.filter { it.sessionId == sessionId }.sortedBy { it.createdAt }

    override fun observeBySession(sessionId: String): Flow<List<BranchNodeEntity>> =
        flowOf(nodes.filter { it.sessionId == sessionId }.sortedBy { it.createdAt })

    override suspend fun insert(node: BranchNodeEntity) {
        nodes.add(node)
    }

    override suspend fun getById(id: String): BranchNodeEntity? =
        nodes.firstOrNull { it.id == id }

    override suspend fun updateLabel(id: String, label: String) {
        val idx = nodes.indexOfFirst { it.id == id }
        if (idx >= 0) nodes[idx] = nodes[idx].copy(label = label)
    }
}

// ---------------------------------------------------------------------------
// Builder helpers
// ---------------------------------------------------------------------------

fun sessionOf(
    id: String = "session-1",
    systemPrompt: String = "",
    model: String = "gpt-4o-mini",
    temperature: Float = 1.0f,
    strategy: MemoryStrategy = MemoryStrategy.FULL,
    compressionEnabled: Boolean = false,
    compressionN: Int = 5,
    compressionM: Int = 6,
    slidingWindowN: Int = 5,
    stickyFactsN: Int = 5
) = SessionEntity(
    id = id,
    startedAt = 1000L,
    title = "Test Session",
    systemPrompt = systemPrompt,
    model = model,
    temperature = temperature,
    compressionEnabled = compressionEnabled,
    compressionN = compressionN,
    compressionM = compressionM,
    memoryStrategy = strategy.name,
    slidingWindowN = slidingWindowN,
    stickyFactsN = stickyFactsN
)

fun messageEntity(
    id: String,
    sessionId: String,
    content: String,
    isFromUser: Boolean,
    createdAt: Long,
    branchNodeId: String? = null
) = MessageEntity(
    id = id,
    sessionId = sessionId,
    content = content,
    isFromUser = isFromUser,
    createdAt = createdAt,
    branchNodeId = branchNodeId
)

fun makeUserMessage(
    idx: Int,
    sessionId: String = "session-1",
    nodeId: String? = null
) = messageEntity("msg-u$idx", sessionId, "User message $idx", true, idx.toLong() * 2, nodeId)

fun makeAssistantMessage(
    idx: Int,
    sessionId: String = "session-1",
    nodeId: String? = null
) = messageEntity("msg-a$idx", sessionId, "Assistant reply $idx", false, idx.toLong() * 2 + 1, nodeId)

fun makeMockMemory(contextString: String = ""): AgentMemory {
    val memory = mock<AgentMemory>()
    whenever(memory.toContextString()).thenReturn(contextString)
    return memory
}

fun makeMockUserProfile(contextString: String = ""): UserProfileRepository {
    val repo = mock<UserProfileRepository>()
    whenever(repo.toContextString()).thenReturn(contextString)
    return repo
}
