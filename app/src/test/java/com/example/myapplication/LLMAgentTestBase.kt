package com.example.myapplication

import com.example.myapplication.agent.AgentMemory
import com.example.myapplication.agent.TaskFsmRepository
import com.example.myapplication.data.db.dao.TaskFsmDao
import com.example.myapplication.data.db.entity.MemoryStrategy
import com.example.myapplication.data.db.entity.TaskFsmEntity
import com.example.myapplication.data.repository.Constraints
import com.example.myapplication.data.repository.ConstraintsRepository
import com.example.myapplication.data.repository.UserProfileRepository
import com.example.myapplication.domain.model.BranchNode
import com.example.myapplication.domain.model.FactData
import com.example.myapplication.domain.model.MessageData
import com.example.myapplication.domain.model.Session
import com.example.myapplication.domain.model.SessionContextConfig
import com.example.myapplication.domain.model.SummaryData
import com.example.myapplication.domain.repository.BranchNodeRepository
import com.example.myapplication.domain.repository.FactRepository
import com.example.myapplication.domain.repository.MessageRepository
import com.example.myapplication.domain.repository.SessionRepository
import com.example.myapplication.domain.repository.SummaryRepository
import com.example.myapplication.platform.Clock
import com.example.myapplication.platform.DateFormatter
import com.example.myapplication.platform.KeyValueStorage
import com.example.myapplication.platform.UuidGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Общая тестовая инфраструктура для LLMAgent тестов.
 * Фаза 1 — Fake Repository реализации (domain-модели, без DAO/Entity).
 */

// ---------------------------------------------------------------------------
// Fake Repository implementations (in-memory, no Room/Android)
// ---------------------------------------------------------------------------

class FakeSessionRepository : SessionRepository {
    val sessions = mutableMapOf<String, Session>()
    var assistantMessageCount = 0

    override fun observeAll(): Flow<List<Session>> =
        flowOf(sessions.values.sortedByDescending { it.startedAt })

    override suspend fun getLatest(): Session? =
        sessions.values.maxByOrNull { it.startedAt }

    override suspend fun getById(id: String): Session? = sessions[id]

    override suspend fun insert(session: Session) {
        sessions[session.id] = session
    }

    override suspend fun updateContext(id: String, config: SessionContextConfig) {
        sessions[id]?.let {
            sessions[id] = it.copy(
                systemPrompt = config.systemPrompt,
                model = config.model,
                temperature = config.temperature,
                compressionEnabled = config.compressionEnabled,
                compressionN = config.compressionN,
                compressionM = config.compressionM,
                memoryStrategy = config.memoryStrategy,
                slidingWindowN = config.slidingWindowN,
                stickyFactsN = config.stickyFactsN
            )
        }
    }

    override suspend fun updateTitle(id: String, title: String) {
        sessions[id]?.let { sessions[id] = it.copy(title = title) }
    }

    override suspend fun countAssistantMessages(sessionId: String): Int = assistantMessageCount
}

class FakeMessageRepository : MessageRepository {
    val messages = mutableListOf<MessageData>()

    override suspend fun insert(message: MessageData) {
        messages.add(message)
    }

    override fun observeBySession(sessionId: String): Flow<List<MessageData>> =
        flowOf(
            messages.filter { it.sessionId == sessionId && it.branchNodeId == null }
                .sortedBy { it.createdAt }
        )

    override suspend fun getByNode(nodeId: String): List<MessageData> =
        messages.filter { it.branchNodeId == nodeId }.sortedBy { it.createdAt }

    override fun observeByNode(nodeId: String): Flow<List<MessageData>> =
        flowOf(messages.filter { it.branchNodeId == nodeId }.sortedBy { it.createdAt })

    override suspend fun getBySession(sessionId: String): List<MessageData> =
        messages.filter { it.sessionId == sessionId }.sortedBy { it.createdAt }

    override suspend fun deleteById(id: String) {
        messages.removeIf { it.id == id }
    }

    override suspend fun markAsError(id: String) {
        val idx = messages.indexOfFirst { it.id == id }
        if (idx >= 0) messages[idx] = messages[idx].copy(isError = true)
    }
}

class FakeSummaryRepository : SummaryRepository {
    val summaries = mutableMapOf<String, SummaryData>()

    override suspend fun getBySession(sessionId: String): SummaryData? = summaries[sessionId]

    override suspend fun upsert(summary: SummaryData) {
        summaries[summary.sessionId] = summary
    }

    override fun observeBySession(sessionId: String): Flow<SummaryData?> =
        flowOf(summaries[sessionId])

    override suspend fun deleteBySession(sessionId: String) {
        summaries.remove(sessionId)
    }
}

class FakeFactRepository : FactRepository {
    val facts = mutableMapOf<String, MutableList<FactData>>()

    override suspend fun getBySession(sessionId: String): List<FactData> =
        facts[sessionId]?.sortedBy { it.factKey } ?: emptyList()

    override suspend fun deleteBySession(sessionId: String) {
        facts.remove(sessionId)
    }

    override suspend fun upsertAll(factList: List<FactData>) {
        factList.forEach { fact ->
            val list = facts.getOrPut(fact.sessionId) { mutableListOf() }
            list.removeIf { it.factKey == fact.factKey }
            list.add(fact)
        }
    }

    override fun observeBySession(sessionId: String): Flow<List<FactData>> =
        flowOf(facts[sessionId] ?: emptyList())
}

class FakeBranchNodeRepository : BranchNodeRepository {
    val nodes = mutableListOf<BranchNode>()

    override suspend fun getBySession(sessionId: String): List<BranchNode> =
        nodes.filter { it.sessionId == sessionId }.sortedBy { it.createdAt }

    override fun observeBySession(sessionId: String): Flow<List<BranchNode>> =
        flowOf(nodes.filter { it.sessionId == sessionId }.sortedBy { it.createdAt })

    override suspend fun insert(node: BranchNode) {
        nodes.add(node)
    }

    override suspend fun getById(id: String): BranchNode? =
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
) = Session(
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

fun messageData(
    id: String,
    sessionId: String,
    content: String,
    isFromUser: Boolean,
    createdAt: Long,
    branchNodeId: String? = null
) = MessageData(
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
) = messageData("msg-u$idx", sessionId, "User message $idx", true, idx.toLong() * 2, nodeId)

fun makeAssistantMessage(
    idx: Int,
    sessionId: String = "session-1",
    nodeId: String? = null
) = messageData("msg-a$idx", sessionId, "Assistant reply $idx", false, idx.toLong() * 2 + 1, nodeId)

class FakeTaskFsmDao : TaskFsmDao {
    val states = mutableMapOf<String, TaskFsmEntity>()

    override suspend fun upsert(entity: TaskFsmEntity) {
        states[entity.sessionId] = entity
    }

    override suspend fun getBySession(sessionId: String): TaskFsmEntity? = states[sessionId]

    override fun observeBySession(sessionId: String): Flow<TaskFsmEntity?> =
        flowOf(states[sessionId])

    override suspend fun deleteBySession(sessionId: String) {
        states.remove(sessionId)
    }
}

fun makeMockTaskFsmRepository(): TaskFsmRepository {
    val dao = FakeTaskFsmDao()
    return TaskFsmRepository(dao)
}

// ---------------------------------------------------------------------------
// Fake platform implementations
// ---------------------------------------------------------------------------

class FakeKeyValueStorage : KeyValueStorage {
    private val map = mutableMapOf<String, String>()
    private val boolMap = mutableMapOf<String, Boolean>()

    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun getBoolean(key: String, default: Boolean): Boolean = boolMap[key] ?: default
    override fun putBoolean(key: String, value: Boolean) { boolMap[key] = value }
    override fun getAll(): Map<String, String> = map.toMap()
    override fun remove(key: String) { map.remove(key); boolMap.remove(key) }
    override fun clear() { map.clear(); boolMap.clear() }
}

class FakeClock(var time: Long = 1000L) : Clock {
    override fun nowMillis(): Long = time
}

class FakeUuidGenerator(private val ids: Iterator<String> = generateSequence(0) { it + 1 }.map { "fake-uuid-$it" }.iterator()) : UuidGenerator {
    override fun generate(): String = ids.next()
}

class FakeDateFormatter : DateFormatter {
    override fun formatSessionTitle(millis: Long): String = "Test Session $millis"
}

fun makeFakeMemory(storage: FakeKeyValueStorage = FakeKeyValueStorage()): AgentMemory =
    AgentMemory(storage)

fun makeMockMemory(contextString: String = ""): AgentMemory {
    if (contextString.isEmpty()) return makeFakeMemory()
    val memory = mock<AgentMemory>()
    whenever(memory.toContextString()).thenReturn(contextString)
    return memory
}

fun makeFakeUserProfile(storage: FakeKeyValueStorage = FakeKeyValueStorage()): UserProfileRepository =
    UserProfileRepository(storage)

fun makeMockUserProfile(contextString: String = ""): UserProfileRepository {
    val repo = mock<UserProfileRepository>()
    whenever(repo.toContextString()).thenReturn(contextString)
    whenever(repo.taskMemory).thenReturn(com.example.myapplication.data.repository.TaskMemory())
    whenever(repo.userInformationContextString()).thenReturn("")
    return repo
}

fun makeFakeConstraintsRepository(enabled: Boolean = false, rules: String = ""): ConstraintsRepository {
    val storage = FakeKeyValueStorage()
    val repo = ConstraintsRepository(storage)
    repo.constraints = Constraints(rules = rules, enabled = enabled)
    return repo
}

fun makeMockConstraintsRepository(enabled: Boolean = false, rules: String = ""): ConstraintsRepository =
    makeFakeConstraintsRepository(enabled, rules)
