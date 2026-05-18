package com.example.myapplication

import com.example.myapplication.data.rag.chunker.FixedSizeChunker
import com.example.myapplication.data.rag.embedder.TfIdfEmbedder
import com.example.myapplication.data.rag.retriever.RagRetriever
import org.junit.Assert.*
import org.junit.Test

class RagRetrieverTest {

    private val embedder = TfIdfEmbedder()
    private val retriever = RagRetriever()

    private val corpus = """
        ## SQL and Relational Databases
        SQL is used to query and manipulate relational databases. SELECT, INSERT, UPDATE and DELETE are core SQL statements.
        Relational databases store data in tables with rows and columns, linked by foreign keys.

        ## Indexing and Performance
        Indexes improve query performance by providing fast access paths to data. B-tree indexes support range queries.
        Without indexes, databases perform full table scans which are slow for large tables.

        ## NoSQL Databases
        NoSQL databases provide flexible schemas and horizontal scaling. MongoDB stores documents in JSON-like format.
        Redis is an in-memory key-value store used for caching. Cassandra provides wide-column storage.

        ## Transactions and Concurrency
        Transactions ensure data consistency through ACID properties. Locking mechanisms prevent concurrent modifications.
        Deadlocks can occur when transactions wait for each other's locks.
    """.trimIndent()

    private fun buildIndex(): Triple<List<com.example.myapplication.data.rag.model.RagChunk>, Map<String, com.example.myapplication.data.rag.model.VocabEntry>, com.example.myapplication.data.rag.model.EmbedResult> {
        val rawChunks = FixedSizeChunker(chunkSize = 200, overlap = 30).chunk(corpus)
        val embedResult = embedder.embed(rawChunks)
        return Triple(embedResult.chunks, embedResult.vocabulary, embedResult)
    }

    @Test
    fun `returns top-K chunks`() {
        val (chunks, vocab, _) = buildIndex()
        val results = retriever.query("SQL relational tables", vocab, chunks, topK = 3)
        assertEquals("Should return exactly 3 chunks", 3, results.size)
    }

    @Test
    fun `returns fewer than topK when not enough chunks exist`() {
        val rawChunks = FixedSizeChunker(chunkSize = 300, overlap = 50).chunk("Short text about databases.")
        val embedResult = embedder.embed(rawChunks)
        val results = retriever.query("databases", embedResult.vocabulary, embedResult.chunks, topK = 10)
        assertTrue("Results should not exceed available chunks", results.size <= embedResult.chunks.size)
    }

    @Test
    fun `query about SQL returns SQL-related chunk first`() {
        val (chunks, vocab, _) = buildIndex()
        val results = retriever.query("SQL SELECT query relational", vocab, chunks, topK = 4)
        assertTrue("Should return at least 1 result", results.isNotEmpty())
        val topText = results.first().text.lowercase()
        assertTrue(
            "Top result should contain SQL-related content",
            topText.contains("sql") || topText.contains("relational") || topText.contains("query")
        )
    }

    @Test
    fun `query about indexing returns index-related chunk`() {
        val (chunks, vocab, _) = buildIndex()
        val results = retriever.query("index performance b-tree", vocab, chunks, topK = 2)
        assertTrue("Should return at least 1 result", results.isNotEmpty())
        val texts = results.joinToString(" ") { it.text.lowercase() }
        assertTrue(
            "Results should mention index/performance",
            texts.contains("index") || texts.contains("performance")
        )
    }

    @Test
    fun `empty chunks returns empty list`() {
        val (_, vocab, _) = buildIndex()
        val results = retriever.query("anything", vocab, emptyList())
        assertTrue(results.isEmpty())
    }

    @Test
    fun `empty vocabulary returns empty list`() {
        val (chunks, _, _) = buildIndex()
        val results = retriever.query("anything", emptyMap(), chunks)
        assertTrue(results.isEmpty())
    }
}
