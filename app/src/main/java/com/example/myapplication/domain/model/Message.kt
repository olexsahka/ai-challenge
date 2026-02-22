package com.example.myapplication.domain.model

data class MessageMeta(
    val inputTokens: Int,
    val outputTokens: Int,
    val durationMs: Long,
    val model: String
)

data class Message(
    val id: String,
    val content: String,
    val isFromUser: Boolean,
    val meta: MessageMeta? = null
)