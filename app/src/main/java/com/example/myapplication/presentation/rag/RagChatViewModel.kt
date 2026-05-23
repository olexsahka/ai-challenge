package com.example.myapplication.presentation.rag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.rag.RagRepository
import com.example.myapplication.data.rag.model.RagChatMessage
import com.example.myapplication.data.rag.model.RagRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class RagChatViewModel(
    private val ragRepository: RagRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RagChatScreenState())
    val state: StateFlow<RagChatScreenState> = _state.asStateFlow()

    init {
        collectIndexStatus()
    }

    private fun collectIndexStatus() {
        viewModelScope.launch {
            ragRepository.isIndexing.collect { indexing ->
                if (indexing) {
                    val current = _state.value.indexStatus
                    if (current !is IndexStatus.Indexing) {
                        _state.value = _state.value.copy(indexStatus = IndexStatus.Indexing(0, 0))
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
                    _state.value = _state.value.copy(indexStatus = IndexStatus.Ready(count, strategy))
                } else {
                    _state.value = _state.value.copy(indexStatus = IndexStatus.Empty)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(indexStatus = IndexStatus.IndexError(e.message ?: "Unknown error"))
            }
        }
    }

    fun sendMessage(text: String, isRag: Boolean) {
        if (text.isBlank()) return
        val userMessage = RagChatMessage(
            id = UUID.randomUUID().toString(),
            role = RagRole.USER,
            text = text
        )
        if (isRag) {
            _state.value = _state.value.copy(
                ragMessages = _state.value.ragMessages + userMessage,
                isSending = true,
                error = null
            )
        } else {
            _state.value = _state.value.copy(
                noRagMessages = _state.value.noRagMessages + userMessage,
                isSending = true,
                error = null
            )
        }
        viewModelScope.launch {
            try {
                if (isRag) {
                    val ragAnswer = ragRepository.askWithRag(text)
                    val assistantMessage = RagChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = RagRole.ASSISTANT,
                        text = ragAnswer.answer,
                        sources = ragAnswer.sources
                    )
                    _state.value = _state.value.copy(
                        ragMessages = _state.value.ragMessages + assistantMessage,
                        isSending = false
                    )
                } else {
                    val answer = ragRepository.askWithoutRag(text)
                    val assistantMessage = RagChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = RagRole.ASSISTANT,
                        text = answer
                    )
                    _state.value = _state.value.copy(
                        noRagMessages = _state.value.noRagMessages + assistantMessage,
                        isSending = false
                    )
                }
            } catch (e: Exception) {
                val errorMessage = RagChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = RagRole.ASSISTANT,
                    text = e.message ?: "Unknown error",
                    isError = true
                )
                if (isRag) {
                    _state.value = _state.value.copy(
                        ragMessages = _state.value.ragMessages + errorMessage,
                        isSending = false,
                        error = e.message
                    )
                } else {
                    _state.value = _state.value.copy(
                        noRagMessages = _state.value.noRagMessages + errorMessage,
                        isSending = false,
                        error = e.message
                    )
                }
            }
        }
    }

    fun onTabSelected(tab: ChatTab) {
        _state.value = _state.value.copy(selectedTab = tab)
    }

    fun retry() {
        _state.value = _state.value.copy(indexStatus = IndexStatus.Loading)
        loadStatus()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
