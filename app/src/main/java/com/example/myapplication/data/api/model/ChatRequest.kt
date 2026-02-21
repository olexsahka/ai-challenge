package com.example.myapplication.data.api.model

data class ChatRequest(
    val model: String,
    val instructions: String? = null,
    val input: List<InputMessage>,
    val maxOutputTokens: Int? = null,
    val temperature: Float = 1.0f
)

data class InputMessage(
    val role: String,
    val content: String
)
