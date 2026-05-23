package com.example.myapplication.presentation.rag

import com.example.myapplication.data.rag.model.ChunkingStrategy
import com.example.myapplication.data.rag.model.RagChatMessage

enum class ChatTab { RAG, NO_RAG }

sealed class IndexStatus {
    object Loading : IndexStatus()
    object Empty : IndexStatus()
    data class Indexing(val current: Int, val total: Int) : IndexStatus()
    data class Ready(val chunkCount: Int, val strategy: ChunkingStrategy) : IndexStatus()
    data class IndexError(val message: String) : IndexStatus()
}

data class RagChatScreenState(
    val indexStatus: IndexStatus = IndexStatus.Loading,
    val ragMessages: List<RagChatMessage> = emptyList(),
    val noRagMessages: List<RagChatMessage> = emptyList(),
    val selectedTab: ChatTab = ChatTab.RAG,
    val isSending: Boolean = false,
    val error: String? = null
)
