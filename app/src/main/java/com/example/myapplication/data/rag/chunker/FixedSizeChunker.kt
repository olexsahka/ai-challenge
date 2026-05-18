package com.example.myapplication.data.rag.chunker

import com.example.myapplication.data.rag.model.ChunkingStrategy
import com.example.myapplication.data.rag.model.RagChunk

private val HEADING_REGEX = Regex("^#{1,6}\\s+.+$", RegexOption.MULTILINE)

open class FixedSizeChunker(
    private val chunkSize: Int = 500,
    private val overlap: Int = 50,
    private val source: String = "database-concepts.txt",
    private val title: String = "Database Concepts"
) : Chunker {

    override fun chunk(text: String): List<RagChunk> {
        val chunks = mutableListOf<RagChunk>()
        var pos = 0
        var chunkId = 0

        while (pos < text.length) {
            val rawEnd = minOf(pos + chunkSize, text.length)
            val end = if (rawEnd < text.length) {
                snapToWordBoundary(text, rawEnd)
            } else {
                rawEnd
            }
            val chunkText = text.substring(pos, end).trim()
            if (chunkText.isNotEmpty()) {
                val section = extractLastHeading(text, pos)
                chunks.add(
                    RagChunk(
                        chunkId = chunkId++,
                        source = source,
                        title = title,
                        section = section,
                        text = chunkText,
                        strategy = ChunkingStrategy.FIXED_SIZE
                    )
                )
            }
            val nextPos = end - overlap
            if (nextPos <= pos) break
            pos = nextPos
        }
        return chunks
    }

    private fun extractLastHeading(text: String, upToPos: Int): String? {
        val sub = text.substring(0, upToPos)
        return HEADING_REGEX.findAll(sub).lastOrNull()?.value?.trimStart('#', ' ')
    }
}
