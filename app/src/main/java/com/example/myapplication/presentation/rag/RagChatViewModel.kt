package com.example.myapplication.presentation.rag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.rag.RagRepository
import com.example.myapplication.data.rag.model.ChunkingStrategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RagChatViewModel(
    private val ragRepository: RagRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<RagChatUiState>(RagChatUiState.Loading)
    val uiState: StateFlow<RagChatUiState> = _uiState.asStateFlow()

    init {
        collectIndexStatus()
    }

    private fun collectIndexStatus() {
        viewModelScope.launch {
            ragRepository.isIndexing.collect { indexing ->
                if (indexing) {
                    // Keep Indexing state if already set, otherwise show Indexing(0,0)
                    val current = _uiState.value
                    if (current !is RagChatUiState.Indexing) {
                        _uiState.value = RagChatUiState.Indexing(0, 0)
                    }
                } else {
                    loadStatus()
                }
            }
        }
    }

    private fun loadStatus() {
        viewModelScope.launch {
            try {
                val indexed = ragRepository.isIndexed()
                if (indexed) {
                    val count = ragRepository.getChunkCount()
                    val strategy = ragRepository.getIndexedStrategy() ?: ragRepository.getStrategy()
                    _uiState.value = RagChatUiState.Success(count, strategy)
                } else {
                    _uiState.value = RagChatUiState.Empty
                }
            } catch (e: Exception) {
                _uiState.value = RagChatUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun retry() {
        _uiState.value = RagChatUiState.Loading
        loadStatus()
    }
}
