package com.example.myapplication.data.rag.model

data class RagChunk(
    val id: Long = 0,
    val chunkId: Int,
    val source: String,
    val title: String,
    val section: String?,
    val text: String,
    val embedding: FloatArray? = null,
    val strategy: ChunkingStrategy,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RagChunk) return false
        return id == other.id && chunkId == other.chunkId && source == other.source &&
            title == other.title && section == other.section && text == other.text &&
            embedding?.contentEquals(other.embedding ?: floatArrayOf()) == true &&
            strategy == other.strategy && createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + chunkId
        result = 31 * result + source.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + (section?.hashCode() ?: 0)
        result = 31 * result + text.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + strategy.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

enum class ChunkingStrategy { FIXED_SIZE, STRUCTURAL }

data class IndexProgress(val current: Int, val total: Int)

data class VocabEntry(val dimensionIndex: Int, val idf: Float)

data class EmbedResult(
    val chunks: List<RagChunk>,
    val vocabulary: Map<String, VocabEntry>
)
