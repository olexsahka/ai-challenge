package com.example.myapplication

import com.example.myapplication.data.rag.model.ChunkingStrategy
import com.example.myapplication.data.rag.model.RagChunk
import com.example.myapplication.data.rag.reranker.RagReranker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagRerankerTest {

    private val reranker = RagReranker()

    private fun makeChunk(id: Int) = RagChunk(
        id = id.toLong(), chunkId = id, source = "src", title = "T$id",
        section = null, text = "text $id", strategy = ChunkingStrategy.FIXED_SIZE
    )

    @Test
    fun `rerank_allAboveThreshold_returnsAllUpToPostFilterK`() {
        val scored = listOf(
            makeChunk(1) to 0.9f,
            makeChunk(2) to 0.8f,
            makeChunk(3) to 0.7f,
            makeChunk(4) to 0.6f
        )
        val (result, filtered) = reranker.rerank(scored, threshold = 0.5f, postFilterK = 3)
        assertEquals(3, result.size)
        assertEquals(0, filtered)
    }

    @Test
    fun `rerank_someBelowThreshold_filtersThemOut`() {
        val scored = listOf(
            makeChunk(1) to 0.9f,
            makeChunk(2) to 0.3f,
            makeChunk(3) to 0.1f
        )
        val (result, filtered) = reranker.rerank(scored, threshold = 0.5f, postFilterK = 10)
        assertEquals(1, result.size)
        assertEquals(2, filtered)
    }

    @Test
    fun `rerank_allBelowThreshold_returnsEmpty`() {
        val scored = listOf(
            makeChunk(1) to 0.1f,
            makeChunk(2) to 0.05f
        )
        val (result, filtered) = reranker.rerank(scored, threshold = 0.5f, postFilterK = 5)
        assertTrue(result.isEmpty())
        assertEquals(2, filtered)
    }

    @Test
    fun `rerank_emptyInput_returnsEmpty`() {
        val (result, filtered) = reranker.rerank(emptyList(), threshold = 0.5f, postFilterK = 3)
        assertTrue(result.isEmpty())
        assertEquals(0, filtered)
    }

    @Test
    fun `rerank_thresholdExactlyAtScore_isIncluded`() {
        val scored = listOf(makeChunk(1) to 0.15f)
        val (result, filtered) = reranker.rerank(scored, threshold = 0.15f, postFilterK = 5)
        assertEquals(1, result.size)
        assertEquals(0, filtered)
    }

    @Test
    fun `rerank_postFilterKLimitsResult`() {
        val scored = (1..10).map { makeChunk(it) to (1f - it * 0.05f) }
        val (result, _) = reranker.rerank(scored, threshold = 0.0f, postFilterK = 4)
        assertEquals(4, result.size)
    }

    @Test
    fun `rerank_preservesOrderOfInput`() {
        val scored = listOf(
            makeChunk(1) to 0.9f,
            makeChunk(2) to 0.8f,
            makeChunk(3) to 0.7f
        )
        val (result, _) = reranker.rerank(scored, threshold = 0.0f, postFilterK = 3)
        assertEquals(1L, result[0].first.id)
        assertEquals(2L, result[1].first.id)
        assertEquals(3L, result[2].first.id)
    }
}
