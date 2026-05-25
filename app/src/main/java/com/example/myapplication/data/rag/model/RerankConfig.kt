package com.example.myapplication.data.rag.model

data class RerankConfig(
    val rerankEnabled: Boolean = false,
    val threshold: Float = 0.15f,
    val preFilterK: Int = 10,
    val postFilterK: Int = 3,
    val queryRewriteEnabled: Boolean = false
)
