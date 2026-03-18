package com.example.myapplication.domain.model

data class MessageData(
    val id: String,
    val sessionId: String,
    val content: String,
    val isFromUser: Boolean,
    val createdAt: Long,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val durationMs: Long = 0,
    val model: String = "",
    val branchNodeId: String? = null,
    val isError: Boolean = false
)
