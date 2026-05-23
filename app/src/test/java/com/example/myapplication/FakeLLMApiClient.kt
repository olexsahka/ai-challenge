package com.example.myapplication

import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.ChatResponse
import com.example.myapplication.data.api.model.ModelsResponse
import com.example.myapplication.data.api.model.OutputContent
import com.example.myapplication.data.api.model.OutputItem
import com.example.myapplication.domain.api.LLMApiClient

fun buildFakeResponse(text: String): ChatResponse = ChatResponse(
    id = "fake-id",
    output = listOf(
        OutputItem(
            type = "message",
            content = listOf(OutputContent(type = "output_text", text = text))
        )
    ),
    usage = null
)

class FakeLLMApiClient(
    private val response: ChatResponse = buildFakeResponse("fake answer")
) : LLMApiClient {
    var lastRequest: ChatRequest? = null
    var callCount = 0

    override suspend fun sendMessage(request: ChatRequest): ChatResponse {
        lastRequest = request
        callCount++
        return response
    }

    override suspend fun getModels(): ModelsResponse = ModelsResponse(`object` = "list", data = emptyList())
}

class ThrowingLLMApiClient(private val error: Exception = Exception("API error")) : LLMApiClient {
    override suspend fun sendMessage(request: ChatRequest): ChatResponse = throw error
    override suspend fun getModels(): ModelsResponse = ModelsResponse(`object` = "list", data = emptyList())
}
