package com.example.myapplication.presentation.chat

import com.example.myapplication.domain.model.Message

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)
