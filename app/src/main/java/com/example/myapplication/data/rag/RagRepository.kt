package com.example.myapplication.data.rag

import android.content.SharedPreferences
import com.example.myapplication.data.db.dao.RagChunkDao
import com.example.myapplication.data.rag.model.ChunkingStrategy
import com.example.myapplication.data.rag.model.IndexProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val KEY_STRATEGY = "chunking_strategy"
private const val KEY_LAST_INDEXED_AT = "last_indexed_at"
private const val KEY_INDEXED_STRATEGY = "indexed_strategy"

class RagRepository(
    private val prefs: SharedPreferences,
    private val indexer: RagIndexer,
    private val chunkDao: RagChunkDao
) {
    private val _isIndexing = MutableStateFlow(false)
    val isIndexing: StateFlow<Boolean> = _isIndexing.asStateFlow()

    fun getStrategy(): ChunkingStrategy {
        val name = prefs.getString(KEY_STRATEGY, ChunkingStrategy.FIXED_SIZE.name)
        return try {
            ChunkingStrategy.valueOf(name ?: ChunkingStrategy.FIXED_SIZE.name)
        } catch (e: IllegalArgumentException) {
            ChunkingStrategy.FIXED_SIZE
        }
    }

    fun setStrategy(strategy: ChunkingStrategy) {
        prefs.edit().putString(KEY_STRATEGY, strategy.name).apply()
    }

    fun getIndexedStrategy(): ChunkingStrategy? {
        val name = prefs.getString(KEY_INDEXED_STRATEGY, null) ?: return null
        return try {
            ChunkingStrategy.valueOf(name)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    suspend fun isIndexed(): Boolean {
        val lastIndexedAt = prefs.getLong(KEY_LAST_INDEXED_AT, 0L)
        return lastIndexedAt > 0L && chunkDao.countAll() > 0
    }

    suspend fun getChunkCount(): Int = chunkDao.countAll()

    suspend fun reindex(
        strategy: ChunkingStrategy,
        onProgress: (IndexProgress) -> Unit
    ) {
        if (_isIndexing.value) return
        _isIndexing.value = true
        try {
            indexer.reindex(strategy, onProgress)
            prefs.edit()
                .putLong(KEY_LAST_INDEXED_AT, System.currentTimeMillis())
                .putString(KEY_INDEXED_STRATEGY, strategy.name)
                .apply()
        } finally {
            _isIndexing.value = false
        }
    }
}
