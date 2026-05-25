package com.example.myapplication.data.rag.model

enum class RagRole { USER, ASSISTANT }

data class RagChatMessage(
    val id: String,
    val role: RagRole,
    val text: String,
    val sources: List<RagChunk> = emptyList(),
    val isError: Boolean = false,
    val scores: List<Float> = emptyList(),
    val rewrittenQuery: String? = null,
    val filteredCount: Int = 0
)
