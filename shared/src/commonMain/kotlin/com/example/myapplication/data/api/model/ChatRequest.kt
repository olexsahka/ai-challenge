package com.example.myapplication.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val model: String,
    val instructions: String? = null,
    val input: List<InputMessage>,
    @SerialName("max_tokens") val maxOutputTokens: Int? = null,
    val temperature: Float? = null
)

@Serializable
data class InputMessage(
    val role: String,
    val content: String
)
