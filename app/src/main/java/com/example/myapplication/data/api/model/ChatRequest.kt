package com.example.myapplication.data.api.model

data class ChatRequest(
    val model: String,
    val instructions: String? = null,
    val input: List<InputMessage>,
    val max_output_tokens: Int? = null
)

data class InputMessage(
    val role: String,
    val content: String
)
