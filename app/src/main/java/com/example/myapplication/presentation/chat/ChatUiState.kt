package com.example.myapplication.presentation.chat

import com.example.myapplication.domain.model.Message
import com.example.myapplication.domain.model.RestrictionProfile
import com.example.myapplication.domain.model.Settings

data class PaneState(
    val profile: RestrictionProfile,
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false
)

data class ChatUiState(
    val unrestrictedMessages: List<Message> = emptyList(),
    val isUnrestrictedLoading: Boolean = false,
    val profilePanes: List<PaneState> = emptyList(),
    val error: String? = null,
    val settings: Settings = Settings(),
    val isSettingsDialogVisible: Boolean = false
)
