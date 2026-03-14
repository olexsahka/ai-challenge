package com.example.myapplication.data.api.model

import com.google.gson.annotations.SerializedName

data class ChatRequest(
    val model: String,
    val instructions: String? = null,
    val input: List<InputMessage>,
    @SerializedName("max_tokens") val maxOutputTokens: Int? = null,
    val temperature: Float? = null
)

data class InputMessage(
    val role: String,
    val content: String
)
