package com.example.myapplication.data.api.model

data class ChatRequest(
    val model: String,
    val input: List<InputMessage>
)

data class InputMessage(
    val role: String,
    val content: String
)
