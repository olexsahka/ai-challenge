package com.example.myapplication.data.rag.model

data class RagAnswer(
    val answer: String,
    val sources: List<RagChunk>,
    val rewrittenQuery: String? = null,
    val scores: List<Float> = emptyList(),
    val filteredCount: Int = 0
)
