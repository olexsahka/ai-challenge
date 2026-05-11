package com.example.myapplication.presentation.agent

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.agent.AgentRunner
import com.example.myapplication.agent.AgentStepType
import com.example.myapplication.agent.LLMAgent
import com.example.myapplication.agent.MemoryEntry
import com.example.myapplication.data.mcp.McpConnectionStatus
import com.example.myapplication.data.mcp.McpRepository
import com.example.myapplication.data.mcp.TelegramMcpRepository
import com.example.myapplication.data.reminder.CryptoMcpRepository
import com.example.myapplication.data.reminder.ReminderEvent
import com.example.myapplication.data.reminder.ReminderManager
import com.example.myapplication.data.repository.Constraints
import com.example.myapplication.data.repository.ConstraintsRepository
import com.example.myapplication.data.repository.TaskMemory
import com.example.myapplication.data.repository.UserInformation
import com.example.myapplication.data.repository.UserProfileRepository
import com.example.myapplication.service.ReminderForegroundService
import com.example.myapplication.data.db.entity.MemoryStrategy
import com.example.myapplication.domain.model.BranchNode
import com.example.myapplication.domain.model.FactData
import com.example.myapplication.domain.model.Message
import com.example.myapplication.domain.model.Session
import com.example.myapplication.domain.model.SessionContextConfig
import com.example.myapplication.domain.model.TaskFsmState
import com.example.myapplication.domain.model.SummaryData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

data class AgentUiState(
    val sessions: List<Session> = emptyList(),
    val activeSession: Session? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val memories: List<MemoryEntry> = emptyList(),
    val showSettings: Boolean = false,
    val activeSummary: SummaryData? = null,
    val activeFacts: List<FactData> = emptyList(),
    val branchNodes: List<BranchNode> = emptyList(),
    val activeNodeId: String? = null,
    val userInformation: UserInformation = UserInformation(),
    val taskMemory: TaskMemory = TaskMemory(),
    val taskFsmState: TaskFsmState? = null,
    val constraints: Constraints = Constraints(),
    val vkusVillEnabled: Boolean = false,
    val mcpStatus: McpConnectionStatus = McpConnectionStatus.Disconnected,
    val telegramEnabled: Boolean = false,
    val telegramMcpStatus: McpConnectionStatus = McpConnectionStatus.Disconnected,
    val reminderEnabled: Boolean = false,
    val reminderStatus: McpConnectionStatus = McpConnectionStatus.Disconnected
)

class AgentViewModel(
    private val agent: LLMAgent,
    private val userProfileRepository: UserProfileRepository,
    private val constraintsRepository: ConstraintsRepository,
    private val mcpRepository: McpRepository,
    private val agentRunner: AgentRunner,
    private val telegramMcpRepository: TelegramMcpRepository,
    private val reminderRepository: ReminderManager,
    private val cryptoMcpRepository: CryptoMcpRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    private val sessionJobs = mutableMapOf<String, Job>()

    private val _activeNodeId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<Message>> = combine(_activeSessionId, _activeNodeId) { sessionId, nodeId ->
        sessionId to nodeId
    }.flatMapLatest { (sessionId, nodeId) ->
        when {
            sessionId == null -> flowOf(emptyList())
            nodeId != null -> agent.observeNodeMessages(sessionId, nodeId)
            else -> agent.observeMessages(sessionId)
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            agent.observeSessions().collect { sessions ->
                _uiState.update { it.copy(sessions = sessions) }
            }
        }
        viewModelScope.launch {
            val last = agent.getOrRestoreLastSession()
            if (last != null) activateSession(last)
        }
        refreshMemories()
        refreshProfile()
        refreshConstraints()
        refreshMcpState()
        refreshTelegramMcpState()
        refreshReminderState()
        observeReminderEvents()
    }

    fun newSession() {
        agentRunner.resetHistory()
        viewModelScope.launch {
            val session = agent.createSession()
            activateSession(session)
        }
    }

    fun selectSession(session: Session) {
        agentRunner.resetHistory()
        activateSession(session)
    }

    fun sendMessage(text: String) {
        val sessionId = _uiState.value.activeSession?.id ?: return
        if (text.isBlank() || _uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            if (_uiState.value.vkusVillEnabled || _uiState.value.telegramEnabled) {
                val nodeId = _uiState.value.activeNodeId
                runAgentWithMcp(sessionId, text, nodeId)
            } else {
                val strategy = _uiState.value.activeSession?.memoryStrategy
                val nodeId = _uiState.value.activeNodeId
                if (strategy == MemoryStrategy.BRANCHING.name && nodeId != null) {
                    agent.sendMessageToNode(sessionId, nodeId, text).onFailure { e ->
                        _uiState.update { it.copy(error = e.message ?: "Error") }
                    }
                } else {
                    agent.sendMessage(sessionId, text).onFailure { e ->
                        _uiState.update { it.copy(error = e.message ?: "Error") }
                    }
                }
            }
            _uiState.update { it.copy(isLoading = false) }
            refreshMemories()
        }
    }

    private suspend fun runAgentWithMcp(sessionId: String, userTask: String, nodeId: String?) {
        agent.saveUserMessage(sessionId, userTask, nodeId)
        agentRunner.run(userTask) { step ->
            if (step.type == AgentStepType.FINAL_ANSWER) {
                agent.saveAssistantMessage(sessionId, step.content, nodeId)
            }
        }
    }

    fun forkCurrentNode(label: String = "") {
        val sessionId = _uiState.value.activeSession?.id ?: return
        val nodeId = _uiState.value.activeNodeId ?: return
        viewModelScope.launch {
            val newNode = agent.forkNode(sessionId, nodeId, label)
            _activeNodeId.value = newNode.id
            _uiState.update { it.copy(activeNodeId = newNode.id) }
        }
    }

    fun selectBranchNode(node: BranchNode) {
        _activeNodeId.value = node.id
        _uiState.update { it.copy(activeNodeId = node.id) }
    }

    fun renameNode(nodeId: String, label: String) {
        viewModelScope.launch { agent.renameNode(nodeId, label) }
    }

    fun showSettings() = _uiState.update { it.copy(showSettings = true) }
    fun hideSettings() = _uiState.update { it.copy(showSettings = false) }

    fun saveSessionContext(config: SessionContextConfig) {
        val sessionId = _uiState.value.activeSession?.id ?: return
        viewModelScope.launch {
            agent.updateSessionContext(sessionId, config)
            val updated = agent.getSession(sessionId)
            if (updated != null) {
                _uiState.update { it.copy(activeSession = updated, showSettings = false) }
                if (config.memoryStrategy == MemoryStrategy.BRANCHING.name && _uiState.value.activeNodeId == null) {
                    val root = agent.getOrCreateRootNode(sessionId)
                    _activeNodeId.value = root.id
                    _uiState.update { it.copy(activeNodeId = root.id) }
                }
            }
        }
    }

    fun forgetMemory(key: String) {
        agent.forgetMemory(key)
        refreshMemories()
    }

    fun forgetAllMemory() {
        agent.forgetAllMemory()
        refreshMemories()
    }

    fun sendLargeTokenTest() {
        val sessionId = _uiState.value.activeSession?.id ?: return
        if (_uiState.value.isLoading) return
        val chunk = "The quick brown fox jumps over the lazy dog. " // ~45 chars
        // ~4 chars per token → 150000 tokens ≈ 600000 chars
        val targetChars = 600_000
        val repetitions = targetChars / chunk.length + 1
        val largeText = chunk.repeat(repetitions) +
                "\n\nThe above text is repeated filler. Please respond with exactly: OK"
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            agent.sendMessage(sessionId, largeText).onFailure { e ->
                _uiState.update { it.copy(error = e.message ?: "Error") }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun activateSession(session: Session) {
        _activeSessionId.value = session.id
        _activeNodeId.value = null
        _uiState.update { it.copy(activeSession = session, activeSummary = null, activeFacts = emptyList(), branchNodes = emptyList(), activeNodeId = null) }

        sessionJobs.values.forEach { it.cancel() }
        sessionJobs.clear()

        sessionJobs["summary"] = viewModelScope.launch {
            agent.observeSummary(session.id).collect { summary ->
                _uiState.update { it.copy(activeSummary = summary) }
            }
        }
        sessionJobs["facts"] = viewModelScope.launch {
            agent.observeFacts(session.id).collect { facts ->
                _uiState.update { it.copy(activeFacts = facts) }
            }
        }
        sessionJobs["branches"] = viewModelScope.launch {
            agent.observeBranchNodes(session.id).collect { nodes ->
                _uiState.update { it.copy(branchNodes = nodes) }
            }
        }
        sessionJobs["fsm"] = viewModelScope.launch {
            agent.observeTaskFsm(session.id).collect { fsm ->
                _uiState.update { it.copy(taskFsmState = fsm) }
            }
        }
        if (session.memoryStrategy == MemoryStrategy.BRANCHING.name) {
            viewModelScope.launch {
                val root = agent.getOrCreateRootNode(session.id)
                _activeNodeId.value = root.id
                _uiState.update { it.copy(activeNodeId = root.id) }
            }
        }
    }

    private fun refreshMemories() {
        _uiState.update { it.copy(memories = agent.getMemories()) }
    }

    private fun refreshProfile() {
        _uiState.update {
            it.copy(
                userInformation = userProfileRepository.userInformation,
                taskMemory = userProfileRepository.taskMemory
            )
        }
    }

    fun initTaskFsm() {
        val sessionId = _uiState.value.activeSession?.id ?: return
        viewModelScope.launch { agent.initTaskFsm(sessionId) }
    }

    fun pauseTask() {
        val sessionId = _uiState.value.activeSession?.id ?: return
        viewModelScope.launch { agent.pauseTask(sessionId) }
    }

    fun resumeTask() {
        val sessionId = _uiState.value.activeSession?.id ?: return
        viewModelScope.launch { agent.resumeTask(sessionId) }
    }

    fun resetTask() {
        val sessionId = _uiState.value.activeSession?.id ?: return
        viewModelScope.launch { agent.resetTask(sessionId) }
    }

    fun runAllStages() {
        val sessionId = _uiState.value.activeSession?.id ?: return
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            agent.enableAutoRun(sessionId)
            agent.continueFromCurrentStage(sessionId).onFailure { e ->
                _uiState.update { it.copy(error = e.message ?: "Error") }
            }
            _uiState.update { it.copy(isLoading = false) }
            refreshMemories()
        }
    }

    fun sendMessageWithAutoRun(text: String) {
        val sessionId = _uiState.value.activeSession?.id ?: return
        if (text.isBlank() || _uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            agent.sendMessageAutoRun(sessionId, text).onFailure { e ->
                _uiState.update { it.copy(error = e.message ?: "Error") }
            }
            _uiState.update { it.copy(isLoading = false) }
            refreshMemories()
        }
    }

    fun stopAutoRun() {
        val sessionId = _uiState.value.activeSession?.id ?: return
        viewModelScope.launch { agent.disableAutoRun(sessionId) }
    }

    fun saveUserInformation(info: UserInformation) {
        userProfileRepository.userInformation = info
        refreshProfile()
    }

    fun saveTaskMemory(task: TaskMemory) {
        userProfileRepository.taskMemory = task
        refreshProfile()
    }

    fun saveConstraints(constraints: Constraints) {
        constraintsRepository.constraints = constraints
        refreshConstraints()
    }

    private fun refreshConstraints() {
        _uiState.update { it.copy(constraints = constraintsRepository.constraints) }
    }

    fun toggleTelegram(enabled: Boolean) {
        telegramMcpRepository.telegramEnabled = enabled
        if (enabled) {
            _uiState.update { it.copy(telegramEnabled = true, telegramMcpStatus = McpConnectionStatus.Connecting) }
            viewModelScope.launch {
                val status = telegramMcpRepository.connect()
                _uiState.update { it.copy(telegramMcpStatus = status) }
            }
        } else {
            telegramMcpRepository.disconnect()
            _uiState.update { it.copy(telegramEnabled = false, telegramMcpStatus = McpConnectionStatus.Disconnected) }
        }
    }

    private fun refreshTelegramMcpState() {
        val enabled = telegramMcpRepository.telegramEnabled
        _uiState.update { it.copy(telegramEnabled = enabled) }
        if (enabled) {
            _uiState.update { it.copy(telegramMcpStatus = McpConnectionStatus.Connecting) }
            viewModelScope.launch {
                val status = telegramMcpRepository.connect()
                _uiState.update { it.copy(telegramMcpStatus = status) }
            }
        }
    }

    private fun refreshMcpState() {
        val enabled = mcpRepository.vkusVillEnabled
        _uiState.update { it.copy(vkusVillEnabled = enabled) }
        if (enabled) {
            _uiState.update { it.copy(mcpStatus = McpConnectionStatus.Connecting) }
            viewModelScope.launch {
                val status = mcpRepository.connect()
                _uiState.update { it.copy(mcpStatus = status) }
            }
        }
    }

    fun toggleVkusVill(enabled: Boolean) {
        mcpRepository.vkusVillEnabled = enabled
        if (enabled) {
            _uiState.update { it.copy(vkusVillEnabled = true, mcpStatus = McpConnectionStatus.Connecting) }
            viewModelScope.launch {
                val status = mcpRepository.connect()
                _uiState.update { it.copy(mcpStatus = status) }
            }
        } else {
            mcpRepository.disconnect()
            _uiState.update { it.copy(vkusVillEnabled = false, mcpStatus = McpConnectionStatus.Disconnected) }
        }
    }

    fun toggleReminder(context: Context, enabled: Boolean) {
        cryptoMcpRepository.cryptoEnabled = enabled
        reminderRepository.isEnabled = enabled
        if (enabled) {
            _uiState.update { it.copy(reminderEnabled = true, reminderStatus = McpConnectionStatus.Connecting) }
            viewModelScope.launch {
                val status = cryptoMcpRepository.connect()
                _uiState.update { it.copy(reminderStatus = status) }
            }
            ReminderForegroundService.start(context)
        } else {
            cryptoMcpRepository.disconnect()
            ReminderForegroundService.stop(context)
            _uiState.update { it.copy(reminderEnabled = false, reminderStatus = McpConnectionStatus.Disconnected) }
        }
    }

    private fun refreshReminderState() {
        val enabled = cryptoMcpRepository.cryptoEnabled
        reminderRepository.isEnabled = enabled
        _uiState.update { it.copy(reminderEnabled = enabled) }
        if (enabled) {
            _uiState.update { it.copy(reminderStatus = McpConnectionStatus.Connecting) }
            viewModelScope.launch {
                val status = cryptoMcpRepository.connect()
                _uiState.update { it.copy(reminderStatus = status) }
            }
        }
    }

    private fun observeReminderEvents() {
        viewModelScope.launch {
            reminderRepository.reminderEvents.collect { event ->
                val sessionId = _uiState.value.activeSession?.id ?: return@collect
                val nodeId = _uiState.value.activeNodeId
                val text = formatReminderMessage(event)
                agent.saveAssistantMessage(sessionId, text, nodeId)
            }
        }
    }

    private fun formatReminderMessage(event: ReminderEvent): String {
        return buildString {
            append("🔔 **${event.symbol}** — ${event.type}\n")
            append(event.message)
            if (event.price.isNotEmpty()) append("\nЦена: ${event.price}")
        }
    }
}
