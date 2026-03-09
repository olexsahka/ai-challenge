package com.example.myapplication.presentation.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.agent.LLMAgent
import com.example.myapplication.agent.MemoryEntry
import com.example.myapplication.data.repository.UserProfileRepository
import com.example.myapplication.data.db.entity.BranchNodeEntity
import com.example.myapplication.data.db.entity.FactEntity
import com.example.myapplication.data.db.entity.MemoryStrategy
import com.example.myapplication.data.db.entity.SessionEntity
import com.example.myapplication.data.db.entity.SummaryEntity
import com.example.myapplication.domain.model.Message
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
    val sessions: List<SessionEntity> = emptyList(),
    val activeSession: SessionEntity? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val memories: List<MemoryEntry> = emptyList(),
    val showSettings: Boolean = false,
    val activeSummary: SummaryEntity? = null,
    val activeFacts: List<FactEntity> = emptyList(),
    val branchNodes: List<BranchNodeEntity> = emptyList(),
    val activeNodeId: String? = null,
    val profileDescription: String = "",
    val profileEnabled: Boolean = false,
    val taskName: String = "",
    val taskDescription: String = "",
    val taskEnabled: Boolean = false
)

class AgentViewModel(
    private val agent: LLMAgent,
    private val userProfileRepository: UserProfileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    private var summaryJob: Job? = null
    private var factsJob: Job? = null
    private var branchJob: Job? = null

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
    }

    fun newSession() {
        viewModelScope.launch {
            val session = agent.createSession()
            activateSession(session)
        }
    }

    fun selectSession(session: SessionEntity) {
        activateSession(session)
    }

    fun sendMessage(text: String) {
        val sessionId = _uiState.value.activeSession?.id ?: return
        if (text.isBlank() || _uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
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
            _uiState.update { it.copy(isLoading = false) }
            refreshMemories()
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

    fun selectBranchNode(node: BranchNodeEntity) {
        _activeNodeId.value = node.id
        _uiState.update { it.copy(activeNodeId = node.id) }
    }

    fun renameNode(nodeId: String, label: String) {
        viewModelScope.launch { agent.renameNode(nodeId, label) }
    }

    fun showSettings() = _uiState.update { it.copy(showSettings = true) }
    fun hideSettings() = _uiState.update { it.copy(showSettings = false) }

    fun saveSessionContext(
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
        val sessionId = _uiState.value.activeSession?.id ?: return
        viewModelScope.launch {
            agent.updateSessionContext(sessionId, systemPrompt, model, temperature, compressionEnabled, compressionN, compressionM, memoryStrategy, slidingWindowN, stickyFactsN)
            val updated = agent.getSession(sessionId)
            if (updated != null) {
                _uiState.update { it.copy(activeSession = updated, showSettings = false) }
                if (memoryStrategy == MemoryStrategy.BRANCHING.name && _uiState.value.activeNodeId == null) {
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

    private fun activateSession(session: SessionEntity) {
        _activeSessionId.value = session.id
        _activeNodeId.value = null
        _uiState.update { it.copy(activeSession = session, activeSummary = null, activeFacts = emptyList(), branchNodes = emptyList(), activeNodeId = null) }
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            agent.observeSummary(session.id).collect { summary ->
                _uiState.update { it.copy(activeSummary = summary) }
            }
        }
        factsJob?.cancel()
        factsJob = viewModelScope.launch {
            agent.observeFacts(session.id).collect { facts ->
                _uiState.update { it.copy(activeFacts = facts) }
            }
        }
        branchJob?.cancel()
        branchJob = viewModelScope.launch {
            agent.observeBranchNodes(session.id).collect { nodes ->
                _uiState.update { it.copy(branchNodes = nodes) }
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
                profileDescription = userProfileRepository.profileDescription,
                profileEnabled = userProfileRepository.profileEnabled,
                taskName = userProfileRepository.taskName,
                taskDescription = userProfileRepository.taskDescription,
                taskEnabled = userProfileRepository.taskEnabled
            )
        }
    }

    fun saveUserProfile(description: String, enabled: Boolean) {
        userProfileRepository.profileDescription = description
        userProfileRepository.profileEnabled = enabled
        refreshProfile()
    }

    fun saveTaskMemory(name: String, description: String, enabled: Boolean) {
        userProfileRepository.taskName = name
        userProfileRepository.taskDescription = description
        userProfileRepository.taskEnabled = enabled
        refreshProfile()
    }
}
