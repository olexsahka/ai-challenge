package com.example.myapplication.data.rag.retriever

import com.example.myapplication.data.rag.embedder.TfIdfEmbedder
import com.example.myapplication.data.rag.model.RagChunk
import com.example.myapplication.data.rag.model.VocabEntry
import kotlin.math.sqrt

class RagRetriever {

    private val embedder = TfIdfEmbedder()

    fun query(
        queryText: String,
        vocabulary: Map<String, VocabEntry>,
        chunks: List<RagChunk>,
        topK: Int = 4
    ): List<RagChunk> {
        if (chunks.isEmpty() || vocabulary.isEmpty()) return emptyList()

        val queryVector = buildQueryVector(queryText, vocabulary)
        return chunks
            .filter { it.embedding != null }
            .map { chunk -> chunk to cosineSimilarity(queryVector, chunk.embedding!!) }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    private fun buildQueryVector(queryText: String, vocabulary: Map<String, VocabEntry>): FloatArray {
        val tokens = embedder.tokenize(queryText)
        val tf = mutableMapOf<String, Int>()
        for (t in tokens) tf[t] = (tf[t] ?: 0) + 1
        val vector = FloatArray(vocabulary.size)
        for ((term, count) in tf) {
            val entry = vocabulary[term] ?: continue
            vector[entry.dimensionIndex] = (count.toFloat() / tokens.size) * entry.idf
        }
        return embedder.l2normalize(vector)
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }
}
