package com.example.myapplication.data.repository

import com.example.myapplication.data.api.AnthropicApi
import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.InputMessage
import com.example.myapplication.domain.model.Message
import com.example.myapplication.domain.model.MessageMeta
import com.example.myapplication.domain.repository.ChatRepository

class ChatRepositoryImpl(private val api: AnthropicApi) : ChatRepository {

    override suspend fun sendMessage(
        messages: List<Message>,
        instructions: String?,
        maxOutputTokens: Int?,
        temperature: Float,
        model: String
    ): Result<Pair<String, MessageMeta>> {
        return try {
            val request = ChatRequest(
                model = model,
                instructions = instructions?.takeIf { it.isNotBlank() },
                input = messages.map { message ->
                    InputMessage(
                        role = if (message.isFromUser) "user" else "assistant",
                        content = message.content
                    )
                },
                maxOutputTokens = maxOutputTokens,
                temperature = if (temperature == 1.0f) null else temperature
            )
            val startMs = System.currentTimeMillis()
            val response = api.sendMessage(request)
            val durationMs = System.currentTimeMillis() - startMs
            val text = response.output
                .firstOrNull { it.type == "message" }
                ?.content
                ?.firstOrNull { it.type == "output_text" }
                ?.text
                ?: return Result.failure(Exception("Empty response from API"))
            val meta = MessageMeta(
                inputTokens = response.usage?.input_tokens ?: 0,
                outputTokens = response.usage?.output_tokens ?: 0,
                durationMs = durationMs,
                model = model
            )
            Result.success(Pair(text, meta))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
