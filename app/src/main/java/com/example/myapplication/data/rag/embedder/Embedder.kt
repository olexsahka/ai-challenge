package com.example.myapplication.data.rag.embedder

import com.example.myapplication.data.rag.model.EmbedResult
import com.example.myapplication.data.rag.model.RagChunk

interface Embedder {
    fun embed(chunks: List<RagChunk>): EmbedResult
}
