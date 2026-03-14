package com.example.myapplication.data.db.entity

data class SessionContextConfig(
    val systemPrompt: String,
    val model: String,
    val temperature: Float,
    val compressionEnabled: Boolean,
    val compressionN: Int,
    val compressionM: Int,
    val memoryStrategy: String,
    val slidingWindowN: Int,
    val stickyFactsN: Int
)
