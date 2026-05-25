package com.example.myapplication.presentation.rag

import com.example.myapplication.data.rag.model.ChunkingStrategy
import com.example.myapplication.data.rag.model.IndexProgress
import com.example.myapplication.data.rag.model.RerankConfig

sealed class RagSettingsUiState {
    object Loading : RagSettingsUiState()
    data class Idle(
        val currentStrategy: ChunkingStrategy,
        val rerankConfig: RerankConfig = RerankConfig()
    ) : RagSettingsUiState()
    data class Saving(val progress: IndexProgress?) : RagSettingsUiState()
    data class Error(val message: String, val strategy: ChunkingStrategy) : RagSettingsUiState()
}
