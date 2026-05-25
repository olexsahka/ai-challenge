package com.example.myapplication.data.rag.reranker

import com.example.myapplication.data.rag.model.RagChunk

class RagReranker {

    /**
     * Filters scored chunks by threshold and returns at most postFilterK items.
     * @param scored list of (chunk, score) pairs, assumed to be sorted by score descending
     * @param threshold minimum score to keep a chunk (inclusive)
     * @param postFilterK maximum number of chunks to return after filtering
     * @return pair of (filtered chunks, number of items removed by threshold filter)
     */
    fun rerank(
        scored: List<Pair<RagChunk, Float>>,
        threshold: Float,
        postFilterK: Int
    ): Pair<List<Pair<RagChunk, Float>>, Int> {
        val afterThreshold = scored.filter { it.second >= threshold }
        val filteredCount = scored.size - afterThreshold.size
        val result = afterThreshold.take(postFilterK)
        return result to filteredCount
    }
}
