package com.example.myapplication.data.rag

import android.content.SharedPreferences
import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.InputMessage
import com.example.myapplication.data.api.model.extractText
import com.example.myapplication.data.db.dao.RagChunkDao
import com.example.myapplication.data.db.dao.RagVocabularyDao
import com.example.myapplication.data.db.entity.RagChunkEntity
import com.example.myapplication.data.db.entity.RagVocabularyEntity
import com.example.myapplication.data.rag.model.ChunkingStrategy
import com.example.myapplication.data.rag.model.IndexProgress
import com.example.myapplication.data.rag.model.RagAnswer
import com.example.myapplication.data.rag.model.RagChunk
import com.example.myapplication.data.rag.model.VocabEntry
import com.example.myapplication.data.rag.retriever.RagRetriever
import com.example.myapplication.domain.api.LLMApiClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val KEY_STRATEGY = "chunking_strategy"
private const val KEY_LAST_INDEXED_AT = "last_indexed_at"
private const val KEY_INDEXED_STRATEGY = "indexed_strategy"

class RagRepository(
    private val prefs: SharedPreferences,
    private val indexer: RagIndexer,
    private val chunkDao: RagChunkDao,
    private val vocabDao: RagVocabularyDao,
    private val llmApiClient: LLMApiClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _isIndexing = MutableStateFlow(false)
    val isIndexing: StateFlow<Boolean> = _isIndexing.asStateFlow()

    private val retriever = RagRetriever()

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

    suspend fun search(query: String, topK: Int = 4): List<RagChunk> =
        withContext(ioDispatcher) {
            if (!isIndexed()) return@withContext emptyList()
            val strategy = getIndexedStrategy() ?: getStrategy()
            val vocabEntities = vocabDao.getAll(strategy.name)
            val chunkEntities = chunkDao.getAll(strategy.name)
            val vocabulary = vocabEntities.toVocabulary()
            val chunks = chunkEntities.map { it.toRagChunk(strategy) }
            retriever.query(query, vocabulary, chunks, topK)
        }

    suspend fun askWithRag(question: String, topK: Int = 4): RagAnswer =
        withContext(ioDispatcher) {
            val chunks = search(question, topK)
            val request = ChatRequest(
                model = "gpt-4o-mini",
                temperature = 0.7f,
                maxOutputTokens = null,
                instructions = """
                    Ты помощник, отвечающий ТОЛЬКО на основе предоставленного контекста из документа
                    "Oracle Database Concepts 21c". Если ответа в контексте нет — скажи
                    "В предоставленных фрагментах ответа нет". Не выдумывай факты.
                    Отвечай по-русски, кратко (2-5 предложений).
                """.trimIndent(),
                input = listOf(
                    InputMessage(
                        role = "user",
                        content = buildRagPromptContent(chunks, question)
                    )
                )
            )
            val response = llmApiClient.sendMessage(request)
            val answer = response.extractText() ?: ""
            RagAnswer(answer = answer, sources = chunks)
        }

    suspend fun askWithoutRag(question: String): String =
        withContext(ioDispatcher) {
            val request = ChatRequest(
                model = "gpt-4o-mini",
                temperature = 0.7f,
                maxOutputTokens = null,
                instructions = null,
                input = listOf(InputMessage(role = "user", content = question))
            )
            val response = llmApiClient.sendMessage(request)
            response.extractText() ?: ""
        }

    internal fun buildRagPromptContent(chunks: List<RagChunk>, question: String): String {
        val sb = StringBuilder()
        sb.appendLine("Контекст:")
        sb.appendLine()
        if (chunks.isEmpty()) {
            sb.appendLine("Контекст пуст.")
        } else {
            chunks.forEachIndexed { index, chunk ->
                val sectionLabel = chunk.section ?: chunk.title
                sb.appendLine("--- Фрагмент ${index + 1} (раздел: $sectionLabel) ---")
                sb.appendLine(chunk.text)
                if (index < chunks.lastIndex) sb.appendLine()
            }
        }
        sb.appendLine()
        sb.append("Вопрос: $question")
        return sb.toString()
    }

    private fun List<RagVocabularyEntity>.toVocabulary(): Map<String, VocabEntry> =
        associate { it.word to VocabEntry(dimensionIndex = it.dimensionIndex, idf = it.idf) }

    private fun RagChunkEntity.toRagChunk(strategy: ChunkingStrategy): RagChunk =
        RagChunk(
            id = id,
            chunkId = chunkId,
            source = source,
            title = title,
            section = section,
            text = text,
            embedding = embeddingBlob?.toFloatArray(),
            strategy = strategy,
            createdAt = createdAt
        )

    private fun ByteArray.toFloatArray(): FloatArray {
        val buf = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(size / 4) { buf.float }
    }
}
