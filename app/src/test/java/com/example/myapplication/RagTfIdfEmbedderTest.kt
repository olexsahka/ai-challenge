package com.example.myapplication

import com.example.myapplication.data.rag.chunker.FixedSizeChunker
import com.example.myapplication.data.rag.embedder.TfIdfEmbedder
import com.example.myapplication.data.rag.model.ChunkingStrategy
import com.example.myapplication.data.rag.model.RagChunk
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class RagTfIdfEmbedderTest {

    private val embedder = TfIdfEmbedder()

    private val fixture = """
        ## Databases
        A database is an organized collection of structured information stored electronically.
        Database management systems control databases and provide querying capabilities.

        ## SQL
        SQL is the standard language for relational database management systems.
        SQL statements are used to perform tasks such as update data or retrieve data.

        ## Indexes
        An index is a data structure that improves the speed of data retrieval in databases.
        Indexes can be unique or non-unique and support range and equality queries efficiently.
    """.trimIndent()

    private fun makeChunks(): List<RagChunk> {
        return FixedSizeChunker(chunkSize = 200, overlap = 30).chunk(fixture)
    }

    @Test
    fun `embedding dimension equals vocabulary size`() {
        val chunks = makeChunks()
        val result = embedder.embed(chunks)
        val vocabSize = result.vocabulary.size
        for (chunk in result.chunks) {
            assertNotNull("Embedding should not be null", chunk.embedding)
            assertEquals("Embedding size should equal vocab size", vocabSize, chunk.embedding!!.size)
        }
    }

    @Test
    fun `embeddings are L2 normalized`() {
        val chunks = makeChunks()
        val result = embedder.embed(chunks)
        for (chunk in result.chunks) {
            val emb = chunk.embedding!!
            val norm = sqrt(emb.map { it * it }.sum())
            // norm should be ~1.0 or 0.0 (for empty vectors)
            assertTrue(
                "L2 norm should be ~1.0 or 0.0 but was $norm",
                norm < 0.001f || kotlin.math.abs(norm - 1.0f) < 0.001f
            )
        }
    }

    @Test
    fun `vocabulary contains expected terms`() {
        val chunks = makeChunks()
        val result = embedder.embed(chunks)
        assertTrue("Vocab should not be empty", result.vocabulary.isNotEmpty())
        assertTrue("Should contain 'database'", result.vocabulary.containsKey("database"))
    }

    @Test
    fun `stop words are excluded from vocabulary`() {
        val chunks = makeChunks()
        val result = embedder.embed(chunks)
        TfIdfEmbedder.STOP_WORDS.forEach { stopWord ->
            assertFalse("Stop word '$stopWord' should not be in vocab", result.vocabulary.containsKey(stopWord))
        }
    }

    @Test
    fun `empty input returns empty result`() {
        val result = embedder.embed(emptyList())
        assertTrue(result.chunks.isEmpty())
        assertTrue(result.vocabulary.isEmpty())
    }

    @Test
    fun `all chunks in result have embeddings`() {
        val chunks = makeChunks()
        val result = embedder.embed(chunks)
        assertEquals(chunks.size, result.chunks.size)
        for (chunk in result.chunks) {
            assertNotNull(chunk.embedding)
        }
    }

    @Test
    fun `vocabulary entries have unique dimension indices`() {
        val chunks = makeChunks()
        val result = embedder.embed(chunks)
        val indices = result.vocabulary.values.map { it.dimensionIndex }
        assertEquals("Dimension indices should be unique", indices.size, indices.toSet().size)
    }
}
