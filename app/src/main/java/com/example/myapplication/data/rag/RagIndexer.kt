package com.example.myapplication.data.rag

import com.example.myapplication.data.db.dao.RagChunkDao
import com.example.myapplication.data.db.dao.RagVocabularyDao
import com.example.myapplication.data.db.entity.RagChunkEntity
import com.example.myapplication.data.db.entity.RagVocabularyEntity
import com.example.myapplication.data.rag.chunker.FixedSizeChunker
import com.example.myapplication.data.rag.chunker.StructuralChunker
import com.example.myapplication.data.rag.embedder.Embedder
import com.example.myapplication.data.rag.model.ChunkingStrategy
import com.example.myapplication.data.rag.model.IndexProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RagIndexer(
    private val loader: RagAssetLoader,
    private val fixedSizeChunker: FixedSizeChunker,
    private val structuralChunker: StructuralChunker,
    private val embedder: Embedder,
    private val chunkDao: RagChunkDao,
    private val vocabDao: RagVocabularyDao
) {
    suspend fun reindex(
        strategy: ChunkingStrategy,
        onProgress: (IndexProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        // 1. Clear existing data
        chunkDao.deleteAll()
        vocabDao.deleteAll()

        // 2. Load text
        val text = loader.loadText("database-concepts.txt")

        // 3. Chunk
        val chunker = when (strategy) {
            ChunkingStrategy.FIXED_SIZE -> fixedSizeChunker
            ChunkingStrategy.STRUCTURAL -> structuralChunker
        }
        val chunks = chunker.chunk(text)

        if (chunks.isEmpty()) {
            onProgress(IndexProgress(0, 0))
            return@withContext
        }

        // 4. Embed
        val embedResult = embedder.embed(chunks)

        val total = embedResult.chunks.size
        onProgress(IndexProgress(0, total))

        // 5. Insert chunks in batches
        val strategyName = strategy.name
        val chunkEntities = embedResult.chunks.mapIndexed { idx, chunk ->
            onProgress(IndexProgress(idx + 1, total))
            RagChunkEntity(
                chunkId = chunk.chunkId,
                source = chunk.source,
                title = chunk.title,
                section = chunk.section,
                text = chunk.text,
                embeddingBlob = chunk.embedding?.toByteArray(),
                strategy = strategyName,
                createdAt = chunk.createdAt
            )
        }
        chunkDao.insertAll(chunkEntities)

        // 6. Insert vocabulary
        val vocabEntities = embedResult.vocabulary.map { (word, entry) ->
            RagVocabularyEntity(
                word = word,
                dimensionIndex = entry.dimensionIndex,
                idf = entry.idf,
                strategy = strategyName
            )
        }
        vocabDao.insertAll(vocabEntities)
    }
}

private fun FloatArray.toByteArray(): ByteArray =
    ByteBuffer.allocate(4 * size).order(ByteOrder.LITTLE_ENDIAN)
        .also { buf -> forEach { buf.putFloat(it) } }
        .array()
