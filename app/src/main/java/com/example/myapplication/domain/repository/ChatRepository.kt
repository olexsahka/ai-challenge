package com.example.myapplication.domain.repository

import com.example.myapplication.domain.model.Message

interface ChatRepository {
    suspend fun sendMessage(
        messages: List<Message>,
        instructions: String? = null,
        maxOutputTokens: Int? = null,
        temperature: Float = 1.0f
    ): Result<String>
}
