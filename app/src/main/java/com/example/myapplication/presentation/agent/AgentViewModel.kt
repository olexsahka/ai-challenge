package com.example.myapplication.presentation.agent

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.agent.ActiveSessionProvider
import com.example.myapplication.agent.AgentRunner
import com.example.myapplication.agent.AgentStepType
import com.example.myapplication.agent.LLMAgent
import com.example.myapplication.data.composition.BtcCompositionSettings
import com.example.myapplication.data.composition.BtcTrackingController
import com.example.myapplication.data.composition.BtcTrackingMcpProvider
import com.example.myapplication.agent.MemoryEntry
import com.example.myapplication.data.mcp.McpConnectionStatus
import com.example.myapplication.data.mcp.McpRepository
import com.example.myapplication.data.mcp.StatelessMcpRepository
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
import kotlinx.coroutines.delay
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
    val reminderStatus: McpConnectionStatus = McpConnectionStatus.Disconnected,
    val taskSearchEnabled: Boolean = false,
    val taskSearchStatus: McpConnectionStatus = McpConnectionStatus.Disconnected,
    val taskSummarizeEnabled: Boolean = false,
    val taskSummarizeStatus: McpConnectionStatus = McpConnectionStatus.Disconnected,
    val taskSaveEnabled: Boolean = false,
    val taskSaveStatus: McpConnectionStatus = McpConnectionStatus.Disconnected,
    val btcCompositionEnabled: Boolean = false
)

class AgentViewModel(
    private val agent: LLMAgent,
    private val userProfileRepository: UserProfileRepository,
    private val constraintsRepository: ConstraintsRepository,
    private val mcpRepository: McpRepository,
    private val agentRunner: AgentRunner,
    private val telegramMcpRepository: TelegramMcpRepository,
    private val reminderRepository: ReminderManager,
    private val cryptoMcpRepository: CryptoMcpRepository,
    private val taskSearchMcpRepository: StatelessMcpRepository,
    private val taskSummarizeMcpRepository: StatelessMcpRepository,
    private val taskSaveMcpRepository: StatelessMcpRepository,
    private val btcCompositionSettings: BtcCompositionSettings,
    private val btcTrackingMcpProvider: BtcTrackingMcpProvider
) : ViewModel(), ActiveSessionProvider, BtcTrackingController {

    override val activeSessionId: String? get() = _activeSessionId.value

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
        btcTrackingMcpProvider.bind(this)
        refreshBtcCompositionState()
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
        refreshTaskSearchState()
        refreshTaskSummarizeState()
        refreshTaskSaveState()
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
            val anyMcpEnabled = with(_uiState.value) {
                vkusVillEnabled || telegramEnabled || reminderEnabled ||
                taskSearchEnabled || taskSummarizeEnabled || taskSaveEnabled ||
                btcCompositionEnabled
            }
        if (anyMcpEnabled) {
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

    fun toggleTaskSearch(enabled: Boolean) {
        taskSearchMcpRepository.enabled = enabled
        if (enabled) {
            _uiState.update { it.copy(taskSearchEnabled = true, taskSearchStatus = McpConnectionStatus.Connecting) }
            viewModelScope.launch {
                val status = taskSearchMcpRepository.connect()
                _uiState.update { it.copy(taskSearchStatus = status) }
            }
        } else {
            taskSearchMcpRepository.disconnect()
            _uiState.update { it.copy(taskSearchEnabled = false, taskSearchStatus = McpConnectionStatus.Disconnected) }
        }
    }

    private fun refreshTaskSearchState() {
        val enabled = taskSearchMcpRepository.enabled
        _uiState.update { it.copy(taskSearchEnabled = enabled) }
        if (enabled) {
            _uiState.update { it.copy(taskSearchStatus = McpConnectionStatus.Connecting) }
            viewModelScope.launch {
                val status = taskSearchMcpRepository.connect()
                _uiState.update { it.copy(taskSearchStatus = status) }
            }
        }
    }

    fun toggleTaskSummarize(enabled: Boolean) {
        taskSummarizeMcpRepository.enabled = enabled
        if (enabled) {
            _uiState.update { it.copy(taskSummarizeEnabled = true, taskSummarizeStatus = McpConnectionStatus.Connecting) }
            viewModelScope.launch {
                val status = taskSummarizeMcpRepository.connect()
                _uiState.update { it.copy(taskSummarizeStatus = status) }
            }
        } else {
            taskSummarizeMcpRepository.disconnect()
            _uiState.update { it.copy(taskSummarizeEnabled = false, taskSummarizeStatus = McpConnectionStatus.Disconnected) }
        }
    }

    private fun refreshTaskSummarizeState() {
        val enabled = taskSummarizeMcpRepository.enabled
        _uiState.update { it.copy(taskSummarizeEnabled = enabled) }
        if (enabled) {
            _uiState.update { it.copy(taskSummarizeStatus = McpConnectionStatus.Connecting) }
            viewModelScope.launch {
                val status = taskSummarizeMcpRepository.connect()
                _uiState.update { it.copy(taskSummarizeStatus = status) }
            }
        }
    }

    fun toggleTaskSave(enabled: Boolean) {
        taskSaveMcpRepository.enabled = enabled
        if (enabled) {
            _uiState.update { it.copy(taskSaveEnabled = true, taskSaveStatus = McpConnectionStatus.Connecting) }
            viewModelScope.launch {
                val status = taskSaveMcpRepository.connect()
                _uiState.update { it.copy(taskSaveStatus = status) }
            }
        } else {
            taskSaveMcpRepository.disconnect()
            _uiState.update { it.copy(taskSaveEnabled = false, taskSaveStatus = McpConnectionStatus.Disconnected) }
        }
    }

    // Оригинальный запрос пользователя, который агент должен повторять каждую минуту
    private var btcTrackingPrompt: String? = null
    private var btcTickerJob: Job? = null

    override fun startBtcTracking(userPrompt: String) {
        btcTrackingPrompt = userPrompt
        btcCompositionSettings.enabled = true
        _uiState.update { it.copy(btcCompositionEnabled = true) }
        btcTickerJob?.cancel()
        btcTickerJob = viewModelScope.launch {
            while (true) {
                delay(60_000)
                val sessionId = _uiState.value.activeSession?.id ?: continue
                val nodeId = _uiState.value.activeNodeId
                android.util.Log.d("BtcTicker", "tick")
                runBtcTickSilently(sessionId, nodeId)
            }
        }
    }

    // Тихий тик: не пишет сообщение пользователя в чат, только ответ агента
    private suspend fun runBtcTickSilently(sessionId: String, nodeId: String?) {
        val task = "Выполни один шаг BTC-мониторинга: вызови get_price для BTC, сохрани результат через save_to_file (имя файла: btc-snapshot-${System.currentTimeMillis()}.txt), затем вызови list_files, найди предыдущий снапшот, прочитай его через read_file и посчитай дельту цены. Выведи итог."
        agentRunner.run(task) { step ->
            if (step.type == AgentStepType.FINAL_ANSWER) {
                agent.saveAssistantMessage(sessionId, step.content, nodeId)
            }
        }
    }

    fun stopBtcTracking() {
        btcTickerJob?.cancel()
        btcTickerJob = null
        btcCompositionSettings.enabled = false
        _uiState.update { it.copy(btcCompositionEnabled = false) }
    }

    fun toggleBtcComposition(enabled: Boolean) {
        if (enabled) {
            btcCompositionSettings.enabled = true
            _uiState.update { it.copy(btcCompositionEnabled = true) }
        } else {
            stopBtcTracking()
        }
    }

    fun runBtcFlowNow() {
        val sessionId = _uiState.value.activeSession?.id ?: return
        val nodeId = _uiState.value.activeNodeId
        val prompt = btcTrackingPrompt ?: return
        viewModelScope.launch {
            runAgentWithMcp(sessionId, prompt, nodeId)
        }
    }

    private fun refreshBtcCompositionState() {
        // btcTrackingPrompt is not persisted — reset enabled flag on restart to avoid silent no-ops
        if (btcTrackingPrompt == null && btcCompositionSettings.enabled) {
            btcCompositionSettings.enabled = false
        }
        _uiState.update { it.copy(btcCompositionEnabled = btcCompositionSettings.enabled) }
    }

    private fun refreshTaskSaveState() {
        val enabled = taskSaveMcpRepository.enabled
        _uiState.update { it.copy(taskSaveEnabled = enabled) }
        if (enabled) {
            _uiState.update { it.copy(taskSaveStatus = McpConnectionStatus.Connecting) }
            viewModelScope.launch {
                val status = taskSaveMcpRepository.connect()
                _uiState.update { it.copy(taskSaveStatus = status) }
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

    override fun onCleared() {
        super.onCleared()
        btcTrackingMcpProvider.unbind()
    }

    private fun formatReminderMessage(event: ReminderEvent): String {
        return buildString {
            append("🔔 **${event.symbol}** — ${event.type}\n")
            append(event.message)
            if (event.price.isNotEmpty()) append("\nЦена: ${event.price}")
        }
    }
}
