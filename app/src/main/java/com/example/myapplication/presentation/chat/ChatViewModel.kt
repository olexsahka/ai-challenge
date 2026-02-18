package com.example.myapplication.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.repository.SettingsRepository
import com.example.myapplication.domain.model.Message
import com.example.myapplication.domain.model.Settings
import com.example.myapplication.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    private val sendMessageUseCase: SendMessageUseCase,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(settings = settingsRepository.getSettings()) }
    }

    fun sendMessage(content: String) {
        if (content.isBlank()) return

        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            content = content.trim(),
            isFromUser = true
        )

        val settings = _uiState.value.settings
        val splitScreen = settings.showWithoutRestrictions

        val baseInstructions = settings.responseFormatDescription.takeIf { it.isNotBlank() }
        val stopInstruction = settings.stopSequence.takeIf { it.isNotBlank() }?.let {
            "You must stop generating immediately when you would output any of these sequences: $it"
        }

        val instructions = listOfNotNull(baseInstructions, stopInstruction)
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
        val maxOutputTokens = settings.maxOutputTokens

        val updatedMessages = _uiState.value.messages + userMessage

        if (splitScreen) {
            val unrestrictedUserMessage = userMessage.copy(id = UUID.randomUUID().toString())
            val updatedUnrestrictedMessages = _uiState.value.unrestrictedMessages + unrestrictedUserMessage

            _uiState.update {
                it.copy(
                    messages = updatedMessages,
                    isLoading = true,
                    unrestrictedMessages = updatedUnrestrictedMessages,
                    isUnrestrictedLoading = true,
                    error = null
                )
            }

            viewModelScope.launch {
                val restrictedDeferred = async {
                    sendMessageUseCase(updatedMessages, instructions, maxOutputTokens)
                }
                val unrestrictedDeferred = async {
                    sendMessageUseCase(updatedUnrestrictedMessages, null)
                }

                restrictedDeferred.await().fold(
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

                unrestrictedDeferred.await().fold(
                    onSuccess = { responseText ->
                        val assistantMessage = Message(
                            id = UUID.randomUUID().toString(),
                            content = responseText,
                            isFromUser = false
                        )
                        _uiState.update {
                            it.copy(
                                unrestrictedMessages = it.unrestrictedMessages + assistantMessage,
                                isUnrestrictedLoading = false
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isUnrestrictedLoading = false,
                                error = error.message ?: "Unknown error"
                            )
                        }
                    }
                )
            }
        } else {
            _uiState.update {
                it.copy(messages = updatedMessages, isLoading = true, error = null)
            }

            viewModelScope.launch {
                sendMessageUseCase(updatedMessages, instructions, maxOutputTokens).fold(
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
    }

    fun showSettingsDialog() {
        _uiState.update { it.copy(isSettingsDialogVisible = true) }
    }

    fun hideSettingsDialog() {
        _uiState.update { it.copy(isSettingsDialogVisible = false) }
    }

    fun saveSettings(settings: Settings) {
        settingsRepository.saveSettings(settings)
        _uiState.update {
            it.copy(
                settings = settings,
                isSettingsDialogVisible = false,
                unrestrictedMessages = if (!settings.showWithoutRestrictions) emptyList() else it.unrestrictedMessages,
                isUnrestrictedLoading = if (!settings.showWithoutRestrictions) false else it.isUnrestrictedLoading
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
