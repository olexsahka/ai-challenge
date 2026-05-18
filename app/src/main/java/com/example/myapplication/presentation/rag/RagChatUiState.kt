package com.example.myapplication.presentation.rag

import com.example.myapplication.data.rag.model.ChunkingStrategy

sealed class RagChatUiState {
    object Loading : RagChatUiState()
    object Empty : RagChatUiState()
    data class Indexing(val current: Int, val total: Int) : RagChatUiState()
    data class Success(val chunkCount: Int, val strategy: ChunkingStrategy) : RagChatUiState()
    data class Error(val message: String) : RagChatUiState()
}
