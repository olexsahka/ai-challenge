package com.example.myapplication

import com.example.myapplication.data.rag.chunker.FixedSizeChunker
import com.example.myapplication.data.rag.embedder.TfIdfEmbedder
import com.example.myapplication.data.rag.retriever.RagRetriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagRetrieverWithScoresTest {

    private val embedder = TfIdfEmbedder()
    private val retriever = RagRetriever()

    private val corpus = """
        ## SQL Basics
        SQL is used to query relational databases. SELECT, INSERT, UPDATE are core statements.

        ## Indexing
        Indexes improve performance by providing fast access paths to data.

        ## Transactions
        Transactions ensure ACID properties for data consistency.
    """.trimIndent()

    private fun buildIndex(): Triple<
            List<com.example.myapplication.data.rag.model.RagChunk>,
            Map<String, com.example.myapplication.data.rag.model.VocabEntry>,
            Any
            > {
        val rawChunks = FixedSizeChunker(chunkSize = 150, overlap = 20).chunk(corpus)
        val embedResult = embedder.embed(rawChunks)
        return Triple(embedResult.chunks, embedResult.vocabulary, embedResult)
    }

    @Test
    fun `queryWithScores_returnsCorrectCount`() {
        val (chunks, vocab, _) = buildIndex()
        val results = retriever.queryWithScores("SQL query", vocab, chunks, topK = 2)
        assertEquals(2, results.size)
    }

    @Test
    fun `queryWithScores_scoresInDescendingOrder`() {
        val (chunks, vocab, _) = buildIndex()
        val results = retriever.queryWithScores("SQL relational", vocab, chunks, topK = 3)
        assertTrue("Should have results", results.isNotEmpty())
        for (i in 0 until results.size - 1) {
            assertTrue("Scores should be descending", results[i].second >= results[i + 1].second)
        }
    }

    @Test
    fun `queryWithScores_emptyChunks_returnsEmpty`() {
        val (_, vocab, _) = buildIndex()
        val results = retriever.queryWithScores("anything", vocab, emptyList(), topK = 3)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `queryWithScores_query_returnsSubsetOfQueryResults`() {
        val (chunks, vocab, _) = buildIndex()
        val withScores = retriever.queryWithScores("SQL", vocab, chunks, topK = 2)
        val withoutScores = retriever.query("SQL", vocab, chunks, topK = 2)
        assertEquals(withoutScores, withScores.map { it.first })
    }
}
