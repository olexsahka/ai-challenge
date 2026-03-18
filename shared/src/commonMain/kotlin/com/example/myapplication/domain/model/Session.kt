package com.example.myapplication.domain.model

data class Session(
    val id: String,
    val startedAt: Long,
    val title: String,
    val systemPrompt: String = "",
    val model: String = "gpt-4o-mini",
    val temperature: Float = 1.0f,
    val memoryStrategy: String = "FULL",
    val compressionEnabled: Boolean = false,
    val compressionN: Int = 5,
    val compressionM: Int = 6,
    val slidingWindowN: Int = 5,
    val stickyFactsN: Int = 5
)
