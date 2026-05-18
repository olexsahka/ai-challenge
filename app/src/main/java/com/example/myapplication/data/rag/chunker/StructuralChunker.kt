package com.example.myapplication.data.rag.chunker

import com.example.myapplication.data.rag.model.ChunkingStrategy
import com.example.myapplication.data.rag.model.RagChunk

private val HEADING_REGEX = Regex(
    "^(?:Part\\s+\\w+|Chapter\\s+\\d+|#{1,6}\\s+.+)$",
    RegexOption.MULTILINE
)

private val FOOTER_REGEX = Regex(
    "^(?:\\d+\\s*$|Page\\s+\\d+|\\f)",
    RegexOption.MULTILINE
)

open class StructuralChunker(
    private val source: String = "database-concepts.txt",
    private val title: String = "Database Concepts"
) : Chunker {

    override fun chunk(text: String): List<RagChunk> {
        val cleaned = trimFooters(text)
        val sections = splitIntoSections(cleaned)
        val chunks = mutableListOf<RagChunk>()
        var chunkId = 0

        for ((heading, body) in sections) {
            val trimmedBody = body.trim()
            if (trimmedBody.isEmpty()) continue

            if (trimmedBody.length > 2000) {
                val subChunks = splitLargeSection(trimmedBody, heading)
                for (sub in subChunks) {
                    chunks.add(sub.copy(chunkId = chunkId++))
                }
            } else {
                chunks.add(
                    RagChunk(
                        chunkId = chunkId++,
                        source = source,
                        title = title,
                        section = heading.ifEmpty { null },
                        text = trimmedBody,
                        strategy = ChunkingStrategy.STRUCTURAL
                    )
                )
            }
        }
        return chunks
    }

    private fun splitIntoSections(text: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val matches = HEADING_REGEX.findAll(text).toList()

        if (matches.isEmpty()) {
            result.add("" to text)
            return result
        }

        // Content before first heading
        val firstMatchStart = matches.first().range.first
        if (firstMatchStart > 0) {
            val pre = text.substring(0, firstMatchStart).trim()
            if (pre.isNotEmpty()) result.add("" to pre)
        }

        for (i in matches.indices) {
            val match = matches[i]
            val heading = match.value.trim()
            val bodyStart = match.range.last + 1
            val bodyEnd = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            val body = text.substring(bodyStart, bodyEnd)
            result.add(heading to body)
        }
        return result
    }

    private fun trimFooters(text: String): String {
        return FOOTER_REGEX.replace(text, "")
    }

    private fun isHeading(line: String): Boolean = HEADING_REGEX.matches(line.trim())

    private fun isFooter(line: String): Boolean = FOOTER_REGEX.matches(line.trim())

    private fun splitLargeSection(text: String, section: String): List<RagChunk> {
        val subChunks = mutableListOf<RagChunk>()
        val subChunkSize = 1500
        val subOverlap = 100
        var pos = 0
        var subId = 0

        while (pos < text.length) {
            val end = minOf(pos + subChunkSize, text.length)
            val snapped = if (end < text.length) snapToWordBoundary(text, end) else end
            val chunkText = text.substring(pos, snapped).trim()
            if (chunkText.isNotEmpty()) {
                subChunks.add(
                    RagChunk(
                        chunkId = subId++,
                        source = "database-concepts.txt",
                        title = "Database Concepts",
                        section = section.trimStart('#', ' ').ifEmpty { null },
                        text = chunkText,
                        strategy = ChunkingStrategy.STRUCTURAL
                    )
                )
            }
            val nextPos = snapped - subOverlap
            if (nextPos <= pos) break
            pos = nextPos
        }
        return subChunks
    }

}
