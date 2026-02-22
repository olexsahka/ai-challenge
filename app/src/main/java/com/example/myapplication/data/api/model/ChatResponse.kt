package com.example.myapplication.data.api.model

data class ChatResponse(
    val id: String,
    val output: List<OutputItem>,
    val usage: UsageInfo?
)

data class OutputItem(
    val type: String,
    val content: List<OutputContent>?
)

data class OutputContent(
    val type: String,
    val text: String
)

data class UsageInfo(
    val input_tokens: Int,
    val output_tokens: Int
)
