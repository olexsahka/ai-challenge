package com.example.myapplication.data.rag.chunker

import com.example.myapplication.data.rag.model.RagChunk

interface Chunker {
    fun chunk(text: String): List<RagChunk>
}
