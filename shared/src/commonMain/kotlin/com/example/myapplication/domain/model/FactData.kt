package com.example.myapplication.domain.model

data class FactData(
    val sessionId: String,
    val factKey: String,
    val factValue: String,
    val updatedAt: Long = 0L
)
