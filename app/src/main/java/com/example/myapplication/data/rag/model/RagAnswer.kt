package com.example.myapplication.data.rag.model

data class RagAnswer(
    val answer: String,
    val sources: List<RagChunk>
)
