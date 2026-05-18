package com.example.myapplication

import com.example.myapplication.data.rag.chunker.FixedSizeChunker
import com.example.myapplication.data.rag.model.ChunkingStrategy
import org.junit.Assert.*
import org.junit.Test

class RagFixedSizeChunkerTest {

    private val fixture = """
        # Introduction to Databases
        A database is an organized collection of structured information or data, typically stored electronically in a computer system.
        Databases are controlled by a database management system (DBMS). Together, the data and the DBMS, along with the applications
        associated with them, are referred to as a database system.

        ## Relational Databases
        The most common type of database. Data is organized into rows and columns in a series of tables, and the SQL language is used
        to manage the data. Relational databases support ACID transactions: Atomicity, Consistency, Isolation, Durability.
        Tables can be linked via foreign keys to establish relationships between data. This ensures referential integrity.

        ## NoSQL Databases
        Non-relational databases designed for large-scale data storage and for massively-parallel, high-performance data processing
        across a large number of commodity servers. Types include: document stores, key-value stores, wide-column stores, graph databases.
        Examples: MongoDB (document), Redis (key-value), Cassandra (wide-column), Neo4j (graph).

        ## Indexing
        A database index is a data structure that improves the speed of data retrieval operations on a table at the cost of additional
        storage and decreased write speed. Common index types: B-tree, Hash, Bitmap, Composite indexes. Indexes can be clustered or
        non-clustered. A clustered index determines the physical order of data in a table.
    """.trimIndent()

    @Test
    fun `produces at least 3 chunks`() {
        val chunker = FixedSizeChunker(chunkSize = 300, overlap = 50)
        val chunks = chunker.chunk(fixture)
        assertTrue("Expected >= 3 chunks but got ${chunks.size}", chunks.size >= 3)
    }

    @Test
    fun `each chunk does not exceed chunkSize plus boundary snap tolerance`() {
        val chunkSize = 300
        val chunker = FixedSizeChunker(chunkSize = chunkSize, overlap = 50)
        val chunks = chunker.chunk(fixture)
        for (chunk in chunks) {
            assertTrue(
                "Chunk text length ${chunk.text.length} exceeds $chunkSize + 20 tolerance",
                chunk.text.length <= chunkSize + 20
            )
        }
    }

    @Test
    fun `chunks have correct strategy`() {
        val chunker = FixedSizeChunker()
        val chunks = chunker.chunk(fixture)
        for (chunk in chunks) {
            assertEquals(ChunkingStrategy.FIXED_SIZE, chunk.strategy)
        }
    }

    @Test
    fun `chunks have correct source and title`() {
        val chunker = FixedSizeChunker()
        val chunks = chunker.chunk(fixture)
        for (chunk in chunks) {
            assertEquals("database-concepts.txt", chunk.source)
            assertEquals("Database Concepts", chunk.title)
        }
    }

    @Test
    fun `chunkIds are sequential starting from 0`() {
        val chunker = FixedSizeChunker(chunkSize = 300, overlap = 50)
        val chunks = chunker.chunk(fixture)
        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.chunkId)
        }
    }

    @Test
    fun `section extracted from preceding heading`() {
        val chunker = FixedSizeChunker(chunkSize = 300, overlap = 50)
        val chunks = chunker.chunk(fixture)
        // At least one chunk should have a non-null section
        val withSection = chunks.filter { it.section != null }
        assertTrue("Expected some chunks with section, got none", withSection.isNotEmpty())
    }

    @Test
    fun `empty text returns empty list`() {
        val chunker = FixedSizeChunker()
        val chunks = chunker.chunk("")
        assertTrue(chunks.isEmpty())
    }
}
