package com.example.myapplication.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.domain.model.Message
import com.example.myapplication.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(private val sendMessageUseCase: SendMessageUseCase) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun sendMessage(content: String) {
        if (content.isBlank()) return

        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            content = content.trim(),
            isFromUser = true
        )
        val updatedMessages = _uiState.value.messages + userMessage
        _uiState.update { it.copy(messages = updatedMessages, isLoading = true, error = null) }

        viewModelScope.launch {
            sendMessageUseCase(updatedMessages).fold(
                onSuccess = { responseText ->
                    val assistantMessage = Message(
                        id = UUID.randomUUID().toString(),
                        content = responseText,
                        isFromUser = false
                    )
                    _uiState.update {
                        it.copy(
                            messages = it.messages + assistantMessage,
                            isLoading = false
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message ?: "Unknown error")
                    }
                }
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
