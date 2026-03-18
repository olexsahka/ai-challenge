package com.example.myapplication.domain.repository

import com.example.myapplication.domain.model.Message
import com.example.myapplication.domain.model.MessageMeta

interface ChatRepository {
    suspend fun sendMessage(
        messages: List<Message>,
        instructions: String? = null,
        maxOutputTokens: Int? = null,
        temperature: Float = 1.0f,
        model: String = "gpt-4o"
    ): Result<Pair<String, MessageMeta>>
}
