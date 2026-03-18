package com.example.myapplication.domain.model

data class BranchNode(
    val id: String,
    val sessionId: String,
    val parentId: String?,
    val label: String,
    val createdAt: Long = 0L
)
