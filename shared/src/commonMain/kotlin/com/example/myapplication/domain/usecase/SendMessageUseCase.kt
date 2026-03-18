package com.example.myapplication.domain.usecase

import com.example.myapplication.domain.model.Message
import com.example.myapplication.domain.model.MessageMeta
import com.example.myapplication.domain.repository.ChatRepository

class SendMessageUseCase(private val repository: ChatRepository) {
    suspend operator fun invoke(
        messages: List<Message>,
        instructions: String? = null,
        maxOutputTokens: Int? = null,
        temperature: Float = 1.0f,
        model: String = "gpt-4o"
    ): Result<Pair<String, MessageMeta>> {
        return repository.sendMessage(messages, instructions, maxOutputTokens, temperature, model)
    }
}
