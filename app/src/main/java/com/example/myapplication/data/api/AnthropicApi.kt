package com.example.myapplication.data.api

import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.ChatResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface AnthropicApi {
    @POST("responses")
    suspend fun sendMessage(@Body request: ChatRequest): ChatResponse
}
