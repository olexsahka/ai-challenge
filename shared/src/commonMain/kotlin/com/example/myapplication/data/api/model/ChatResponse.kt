package com.example.myapplication.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatResponse(
    val id: String,
    val output: List<OutputItem>,
    val usage: UsageInfo?
)

@Serializable
data class OutputItem(
    val type: String,
    val content: List<OutputContent>?
)

@Serializable
data class OutputContent(
    val type: String,
    val text: String
)

@Serializable
data class UsageInfo(
    @SerialName("input_tokens") val input_tokens: Int,
    @SerialName("output_tokens") val output_tokens: Int
)

fun ChatResponse.extractText(): String? =
    output.firstOrNull { it.type == "message" }
        ?.content
        ?.firstOrNull { it.type == "output_text" }
        ?.text
