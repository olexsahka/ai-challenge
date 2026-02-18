package com.example.myapplication.data.repository

import com.example.myapplication.data.api.AnthropicApi
import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.InputMessage
import com.example.myapplication.domain.model.Message
import com.example.myapplication.domain.repository.ChatRepository

class ChatRepositoryImpl(private val api: AnthropicApi) : ChatRepository {

    override suspend fun sendMessage(
        messages: List<Message>,
        instructions: String?,
        maxOutputTokens: Int?
    ): Result<String> {
        return try {
            val request = ChatRequest(
                model = "gpt-4o",
                instructions = instructions?.takeIf { it.isNotBlank() },
                input = messages.map { message ->
                    InputMessage(
                        role = if (message.isFromUser) "user" else "assistant",
                        content = message.content
                    )
                },
                max_output_tokens = maxOutputTokens
            )
            val response = api.sendMessage(request)
            val text = response.output
                .firstOrNull { it.type == "message" }
                ?.content
                ?.firstOrNull { it.type == "output_text" }
                ?.text
                ?: return Result.failure(Exception("Empty response from API"))
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
