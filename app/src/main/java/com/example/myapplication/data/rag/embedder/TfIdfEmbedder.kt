package com.example.myapplication.data.rag.embedder

import com.example.myapplication.data.rag.model.EmbedResult
import com.example.myapplication.data.rag.model.RagChunk
import com.example.myapplication.data.rag.model.VocabEntry
import kotlin.math.ln
import kotlin.math.sqrt

open class TfIdfEmbedder : Embedder {

    override fun embed(chunks: List<RagChunk>): EmbedResult {
        if (chunks.isEmpty()) return EmbedResult(emptyList(), emptyMap())

        val n = chunks.size

        // Pass 1: build vocabulary via streaming — avoid holding all tokenized chunks in memory
        val df = mutableMapOf<String, Int>()
        for (chunk in chunks) {
            for (term in tokenize(chunk.text).toSet()) {
                df[term] = (df[term] ?: 0) + 1
            }
        }

        val vocabulary = mutableMapOf<String, VocabEntry>()
        df.keys.sorted().forEachIndexed { idx, term ->
            val idf = ln((n + 1.0) / ((df[term] ?: 1) + 1.0)).toFloat() + 1f
            vocabulary[term] = VocabEntry(dimensionIndex = idx, idf = idf)
        }
        val vocabSize = vocabulary.size

        // Pass 2: embed one chunk at a time — reuse a single FloatArray to avoid GC pressure
        val embeddedChunks = chunks.map { chunk ->
            val tokens = tokenize(chunk.text)
            val tf = mutableMapOf<String, Int>()
            for (t in tokens) tf[t] = (tf[t] ?: 0) + 1
            val vector = FloatArray(vocabSize)
            for ((term, count) in tf) {
                val entry = vocabulary[term] ?: continue
                vector[entry.dimensionIndex] = (count.toFloat() / tokens.size) * entry.idf
            }
            chunk.copy(embedding = l2normalize(vector))
        }

        return EmbedResult(embeddedChunks, vocabulary)
    }

    fun tokenize(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in STOP_WORDS }
    }

    fun l2normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.map { it * it }.sum())
        if (norm == 0f) return vector
        return FloatArray(vector.size) { vector[it] / norm }
    }

    companion object {
        val STOP_WORDS = setOf("a", "the", "of", "is", "and", "or", "to", "in", "on", "for")
    }
}
