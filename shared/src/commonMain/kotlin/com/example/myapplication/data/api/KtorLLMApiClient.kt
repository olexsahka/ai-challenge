package com.example.myapplication.data.api

import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.ChatResponse
import com.example.myapplication.data.api.model.ModelsResponse
import com.example.myapplication.domain.api.LLMApiClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class KtorLLMApiClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val apiKey: String
) : LLMApiClient {

    override suspend fun sendMessage(request: ChatRequest): ChatResponse =
        httpClient.post("$baseUrl/responses") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun getModels(): ModelsResponse =
        httpClient.get("$baseUrl/models") {
            header("Authorization", "Bearer $apiKey")
        }.body()
}
