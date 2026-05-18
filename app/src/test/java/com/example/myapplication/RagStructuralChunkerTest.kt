package com.example.myapplication

import com.example.myapplication.data.rag.chunker.StructuralChunker
import com.example.myapplication.data.rag.model.ChunkingStrategy
import org.junit.Assert.*
import org.junit.Test

class RagStructuralChunkerTest {

    private val fixture = """
        ## Introduction
        Databases are fundamental to modern software systems. They store, organize and retrieve data efficiently.
        This section covers key concepts in database design and usage.

        ## Relational Model
        The relational model organizes data into tables (relations). Each table has rows (tuples) and columns (attributes).
        Primary keys uniquely identify rows. Foreign keys link tables together, ensuring referential integrity.
        SQL (Structured Query Language) is the standard language for relational databases.

        ## Transactions and ACID
        A transaction is a sequence of operations treated as a single logical unit. ACID stands for:
        Atomicity - all or nothing execution, Consistency - data remains valid, Isolation - concurrent transactions don't interfere,
        Durability - committed transactions persist. Databases use locking and logging to implement ACID guarantees.

        ## Indexing Strategies
        Indexes speed up data retrieval at the cost of extra storage. B-tree indexes support range queries and equality checks.
        Hash indexes are optimal for equality checks only. Composite indexes cover multiple columns. Covering indexes
        include all columns needed by a query, eliminating table lookups.
    """.trimIndent()

    @Test
    fun `splits into separate sections by headings`() {
        val chunker = StructuralChunker()
        val chunks = chunker.chunk(fixture)
        assertTrue("Expected >= 4 chunks, got ${chunks.size}", chunks.size >= 4)
    }

    @Test
    fun `each chunk has correct strategy`() {
        val chunker = StructuralChunker()
        val chunks = chunker.chunk(fixture)
        for (chunk in chunks) {
            assertEquals(ChunkingStrategy.STRUCTURAL, chunk.strategy)
        }
    }

    @Test
    fun `section field corresponds to heading`() {
        val chunker = StructuralChunker()
        val chunks = chunker.chunk(fixture)
        val sections = chunks.mapNotNull { it.section }
        assertTrue("Expected sections from headings", sections.isNotEmpty())
        assertTrue(
            "Expected 'Relational Model' section",
            sections.any { it.contains("Relational Model", ignoreCase = true) }
        )
    }

    @Test
    fun `large section is split into subchunks`() {
        // Create a section larger than 2000 chars
        val longBody = "word ".repeat(500) // 2500 chars
        val text = "## Big Section\n$longBody"
        val chunker = StructuralChunker()
        val chunks = chunker.chunk(text)
        assertTrue("Expected multiple chunks for large section, got ${chunks.size}", chunks.size > 1)
    }

    @Test
    fun `chunkIds are sequential`() {
        val chunker = StructuralChunker()
        val chunks = chunker.chunk(fixture)
        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.chunkId)
        }
    }

    @Test
    fun `chunks have correct source and title`() {
        val chunker = StructuralChunker()
        val chunks = chunker.chunk(fixture)
        for (chunk in chunks) {
            assertEquals("database-concepts.txt", chunk.source)
            assertEquals("Database Concepts", chunk.title)
        }
    }

    @Test
    fun `empty text returns empty list`() {
        val chunker = StructuralChunker()
        val chunks = chunker.chunk("")
        assertTrue(chunks.isEmpty())
    }
}
