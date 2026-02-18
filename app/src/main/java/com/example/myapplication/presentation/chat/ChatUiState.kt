package com.example.myapplication.presentation.chat

import com.example.myapplication.domain.model.Message
import com.example.myapplication.domain.model.Settings

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val unrestrictedMessages: List<Message> = emptyList(),
    val isUnrestrictedLoading: Boolean = false,
    val error: String? = null,
    val settings: Settings = Settings(),
    val isSettingsDialogVisible: Boolean = false
)
