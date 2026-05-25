package com.example.myapplication.presentation.rag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.rag.RagRepository
import com.example.myapplication.data.rag.model.ChunkingStrategy
import com.example.myapplication.data.rag.model.RerankConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RagSettingsViewModel(
    private val ragRepository: RagRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<RagSettingsUiState>(RagSettingsUiState.Loading)
    val uiState: StateFlow<RagSettingsUiState> = _uiState.asStateFlow()

    private val _navigateBack = MutableSharedFlow<Unit>()
    val navigateBack: SharedFlow<Unit> = _navigateBack.asSharedFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    init {
        val current = ragRepository.getStrategy()
        val rerankConfig = ragRepository.getRerankConfig()
        _uiState.value = RagSettingsUiState.Idle(current, rerankConfig)
    }

    fun onSaveRerankConfig(config: RerankConfig) {
        ragRepository.setRerankConfig(config)
        val currentState = _uiState.value
        if (currentState is RagSettingsUiState.Idle) {
            _uiState.value = currentState.copy(rerankConfig = config)
        }
    }

    fun onSave(selected: ChunkingStrategy) {
        val currentState = _uiState.value
        val currentStrategy = when (currentState) {
            is RagSettingsUiState.Idle -> currentState.currentStrategy
            is RagSettingsUiState.Error -> currentState.strategy
            else -> ragRepository.getStrategy()
        }

        if (selected == currentStrategy && ragRepository.getIndexedStrategy() == selected) {
            // Strategy unchanged — treat as "no change needed" but still reindex if not indexed
            viewModelScope.launch {
                if (ragRepository.isIndexed()) {
                    _navigateBack.emit(Unit)
                    return@launch
                }
                doReindex(selected)
            }
            return
        }

        viewModelScope.launch {
            doReindex(selected)
        }
    }

    private suspend fun doReindex(strategy: ChunkingStrategy) {
        ragRepository.setStrategy(strategy)
        _uiState.value = RagSettingsUiState.Saving(null)
        try {
            ragRepository.reindex(strategy) { progress ->
                _uiState.value = RagSettingsUiState.Saving(progress)
            }
            val rerankConfig = ragRepository.getRerankConfig()
            _uiState.value = RagSettingsUiState.Idle(strategy, rerankConfig)
            _snackbarMessage.emit("Индексация завершена")
            kotlinx.coroutines.delay(1500)
            _navigateBack.emit(Unit)
        } catch (e: Exception) {
            _uiState.value = RagSettingsUiState.Error(e.message ?: "Unknown error", strategy)
        }
    }
}
