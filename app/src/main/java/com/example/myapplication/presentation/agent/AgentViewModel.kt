package com.example.myapplication.presentation.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.agent.LLMAgent
import com.example.myapplication.agent.MemoryEntry
import com.example.myapplication.data.db.entity.SessionEntity
import com.example.myapplication.data.db.entity.SummaryEntity
import com.example.myapplication.domain.model.Message
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val activeSummary: SummaryEntity? = null
)

class AgentViewModel(private val agent: LLMAgent) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    private var summaryJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<Message>> = _activeSessionId
        .flatMapLatest { sessionId ->
            if (sessionId == null) flowOf(emptyList())
            else agent.observeMessages(sessionId)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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
            agent.sendMessage(sessionId, text).onFailure { e ->
                _uiState.update { it.copy(error = e.message ?: "Error") }
            }
            _uiState.update { it.copy(isLoading = false) }
            refreshMemories()
        }
    }

    fun showSettings() = _uiState.update { it.copy(showSettings = true) }
    fun hideSettings() = _uiState.update { it.copy(showSettings = false) }

    fun saveSessionContext(
        systemPrompt: String,
        model: String,
        temperature: Float,
        compressionEnabled: Boolean,
        compressionN: Int,
        compressionM: Int
    ) {
        val sessionId = _uiState.value.activeSession?.id ?: return
        viewModelScope.launch {
            agent.updateSessionContext(sessionId, systemPrompt, model, temperature, compressionEnabled, compressionN, compressionM)
            val updated = agent.getSession(sessionId)
            if (updated != null) _uiState.update { it.copy(activeSession = updated, showSettings = false) }
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
        _uiState.update { it.copy(activeSession = session, activeSummary = null) }
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            agent.observeSummary(session.id).collect { summary ->
                _uiState.update { it.copy(activeSummary = summary) }
            }
        }
    }

    private fun refreshMemories() {
        _uiState.update { it.copy(memories = agent.getMemories()) }
    }
}
