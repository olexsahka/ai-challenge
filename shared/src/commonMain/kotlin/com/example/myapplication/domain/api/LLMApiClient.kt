package com.example.myapplication.domain.api

import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.ChatResponse
import com.example.myapplication.data.api.model.ModelsResponse

interface LLMApiClient {
    suspend fun sendMessage(request: ChatRequest): ChatResponse
    suspend fun getModels(): ModelsResponse
}
