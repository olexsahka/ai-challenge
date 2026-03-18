package com.example.myapplication.domain.model

data class SummaryData(
    val sessionId: String,
    val summary: String,
    val coveredMessageCount: Int,
    val updatedAt: Long = 0L
)
