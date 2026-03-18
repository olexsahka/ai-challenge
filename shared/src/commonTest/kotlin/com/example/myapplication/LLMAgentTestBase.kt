package com.example.myapplication

import com.example.myapplication.agent.AgentMemory
import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.ChatResponse
import com.example.myapplication.data.api.model.ModelsResponse
import com.example.myapplication.data.api.model.OutputContent
import com.example.myapplication.data.api.model.OutputItem
import com.example.myapplication.data.api.model.UsageInfo
import com.example.myapplication.data.repository.Constraints
import com.example.myapplication.domain.api.LLMApiClient
import com.example.myapplication.data.repository.ConstraintsRepository
import com.example.myapplication.data.repository.UserProfileRepository
import com.example.myapplication.domain.model.BranchNode
import com.example.myapplication.domain.model.FactData
import com.example.myapplication.domain.model.MemoryStrategy
import com.example.myapplication.domain.model.MessageData
import com.example.myapplication.domain.model.Session
import com.example.myapplication.domain.model.SessionContextConfig
import com.example.myapplication.domain.model.SummaryData
import com.example.myapplication.domain.model.TaskFsmState
import com.example.myapplication.domain.model.TaskStage
import com.example.myapplication.domain.repository.BranchNodeRepository
import com.example.myapplication.domain.repository.FactRepository
import com.example.myapplication.domain.repository.MessageRepository
import com.example.myapplication.domain.repository.SessionRepository
import com.example.myapplication.domain.repository.SummaryRepository
import com.example.myapplication.domain.repository.TaskFsmRepository
import com.example.myapplication.platform.Clock
import com.example.myapplication.platform.DateFormatter
import com.example.myapplication.platform.KeyValueStorage
import com.example.myapplication.platform.UuidGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

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
        val idx = messages.indexOfFirst { it.id == id }
        if (idx >= 0) messages.removeAt(idx)
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
            val idx = list.indexOfFirst { it.factKey == fact.factKey }
            if (idx >= 0) list.removeAt(idx)
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

class FakeTaskFsmRepository : TaskFsmRepository {
    val states = mutableMapOf<String, TaskFsmState>()

    override fun observe(sessionId: String): Flow<TaskFsmState?> =
        flowOf(states[sessionId])

    override suspend fun get(sessionId: String): TaskFsmState? = states[sessionId]

    override suspend fun getOrCreate(sessionId: String): TaskFsmState =
        states.getOrPut(sessionId) { TaskFsmState(sessionId = sessionId) }

    override suspend fun upsert(state: TaskFsmState) {
        states[state.sessionId] = state
    }

    override suspend fun transitionTo(
        sessionId: String, stage: TaskStage, step: Int,
        expectedAction: String, stepCount: Int?
    ) {
        val current = states.getOrPut(sessionId) { TaskFsmState(sessionId = sessionId) }
        states[sessionId] = current.copy(
            stage = stage, step = step, expectedAction = expectedAction,
            stepCount = stepCount ?: current.stepCount
        )
    }

    override suspend fun markDone(sessionId: String) {
        states[sessionId]?.let { states[sessionId] = it.copy(stage = TaskStage.DONE, expectedAction = "finalize") }
    }

    override suspend fun validationFailed(sessionId: String) {
        states[sessionId]?.let { states[sessionId] = it.copy(stage = TaskStage.EXECUTION, expectedAction = "execute_step") }
    }

    override suspend fun pause(sessionId: String) {
        val current = states[sessionId] ?: return
        if (current.paused) return
        states[sessionId] = current.copy(
            paused = true, autoRun = false,
            savedStage = current.stage, savedStep = current.step,
            savedExpectedAction = current.expectedAction, expectedAction = "wait"
        )
    }

    override suspend fun resume(sessionId: String) {
        val current = states[sessionId] ?: return
        if (!current.paused) return
        states[sessionId] = current.copy(
            paused = false,
            stage = current.savedStage ?: current.stage,
            step = current.savedStep ?: current.step,
            expectedAction = current.savedExpectedAction ?: current.expectedAction,
            savedStage = null, savedStep = null, savedExpectedAction = null
        )
    }

    override suspend fun enableAutoRun(sessionId: String) {
        val current = states.getOrPut(sessionId) { TaskFsmState(sessionId = sessionId) }
        states[sessionId] = current.copy(autoRun = true, paused = false)
    }

    override suspend fun disableAutoRun(sessionId: String) {
        states[sessionId]?.let { states[sessionId] = it.copy(autoRun = false) }
    }

    override suspend fun reset(sessionId: String) {
        states[sessionId] = TaskFsmState(sessionId = sessionId)
    }

    override suspend fun setError(sessionId: String) {
        states[sessionId]?.let { states[sessionId] = it.copy(stage = TaskStage.ERROR, autoRun = false, expectedAction = "retry") }
    }

    override suspend fun deleteBySession(sessionId: String) {
        states.remove(sessionId)
    }

    override fun toInstructionsBlock(
        fsm: TaskFsmState, taskMemoryName: String?, taskMemoryDescription: String?,
        taskMemoryEnabled: Boolean, constraintsRules: String?, constraintsEnabled: Boolean
    ): String {
        val sb = StringBuilder()
        if (taskMemoryEnabled) {
            if (!taskMemoryName.isNullOrBlank()) sb.appendLine("Task: ${taskMemoryName.trim()}")
            if (!taskMemoryDescription.isNullOrBlank()) sb.appendLine("Description: ${taskMemoryDescription.trim()}")
            sb.appendLine()
        }
        if (constraintsEnabled && !constraintsRules.isNullOrBlank()) {
            sb.appendLine("Agent constraints (MUST NEVER violate):")
            sb.appendLine(constraintsRules.trim())
            sb.appendLine()
            sb.appendLine("Constraint check rules:")
            sb.appendLine("- Before every action: verify it does not violate any constraint above.")
            sb.appendLine("- After every action: verify the result does not violate any constraint above.")
            sb.appendLine("- If a violation is detected at any point: stop immediately, set stage=error, and report:")
            sb.appendLine("  ERROR: action violates constraint \"<constraint description>\"")
            sb.appendLine("  Options: 1) Update constraints to allow this action. 2) Suggest an alternative request that does not violate constraints.")
            sb.appendLine()
        }
        sb.append("""
Current task state:
  stage: ${fsm.stage.name.lowercase()}
  step: ${fsm.step}
  expected_action: ${fsm.expectedAction}
  paused: ${fsm.paused}

Rules:
- Only perform the action defined in expected_action.
- After completing the action, update the state accordingly.
- Always include the updated state in your response in this format:

State:
stage: <stage>
step: <step>
expected_action: <action>

Action result:
<result of action>
""".trimIndent())
        return sb.toString()
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

class FakeUuidGenerator(
    private val ids: Iterator<String> = generateSequence(0) { it + 1 }.map { "fake-uuid-$it" }.iterator()
) : UuidGenerator {
    override fun generate(): String = ids.next()
}

class FakeDateFormatter : DateFormatter {
    override fun formatSessionTitle(millis: Long): String = "Test Session $millis"
}

fun makeFakeMemory(
    storage: FakeKeyValueStorage = FakeKeyValueStorage(),
    contextString: String = ""
): AgentMemory {
    if (contextString.isEmpty()) return AgentMemory(storage)
    return object : AgentMemory(storage) {
        override fun toContextString(): String = contextString
    }
}

fun makeFakeUserProfile(
    storage: FakeKeyValueStorage = FakeKeyValueStorage(),
    contextString: String = ""
): UserProfileRepository {
    if (contextString.isEmpty()) return UserProfileRepository(storage)
    return object : UserProfileRepository(storage) {
        override fun toContextString(): String = contextString
    }
}

fun makeFakeConstraintsRepository(enabled: Boolean = false, rules: String = ""): ConstraintsRepository {
    val storage = FakeKeyValueStorage()
    val repo = ConstraintsRepository(storage)
    repo.constraints = Constraints(rules = rules, enabled = enabled)
    return repo
}

// ---------------------------------------------------------------------------
// Shared API test helpers
// ---------------------------------------------------------------------------

fun simpleResponse(text: String) = ChatResponse(
    id = "id",
    output = listOf(OutputItem("message", listOf(OutputContent("output_text", text)))),
    usage = UsageInfo(10, 20)
)

class CapturingAnthropicApi(private val response: ChatResponse) : LLMApiClient {
    var lastRequest: ChatRequest? = null
    override suspend fun sendMessage(request: ChatRequest): ChatResponse {
        lastRequest = request
        return response
    }
    override suspend fun getModels() = ModelsResponse(`object` = "list", data = emptyList())
}

class CountingAnthropicApi(private val responses: List<ChatResponse>) : LLMApiClient {
    var callCount = 0
    val requests = mutableListOf<ChatRequest>()
    override suspend fun sendMessage(request: ChatRequest): ChatResponse {
        requests.add(request)
        return responses.getOrElse(callCount) { responses.last() }.also { callCount++ }
    }
    override suspend fun getModels() = ModelsResponse(`object` = "list", data = emptyList())
}

class SequentialAnthropicApi(private val responses: List<ChatResponse>) : LLMApiClient {
    private var idx = 0
    val requests = mutableListOf<ChatRequest>()
    override suspend fun sendMessage(request: ChatRequest): ChatResponse {
        requests.add(request)
        return responses[idx++]
    }
    override suspend fun getModels() = ModelsResponse(`object` = "list", data = emptyList())
}
