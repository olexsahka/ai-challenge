package com.example.myapplication.data.api.model

data class ChatResponse(
    val id: String,
    val output: List<OutputItem>
)

data class OutputItem(
    val type: String,
    val content: List<OutputContent>?
)

data class OutputContent(
    val type: String,
    val text: String
)
