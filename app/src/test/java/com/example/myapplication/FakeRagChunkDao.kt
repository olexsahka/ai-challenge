package com.example.myapplication

import com.example.myapplication.data.db.dao.RagChunkDao
import com.example.myapplication.data.db.entity.RagChunkEntity

class FakeRagChunkDao : RagChunkDao {
    val chunks = mutableListOf<RagChunkEntity>()
    private var nextId = 1L

    override suspend fun insertAll(chunks: List<RagChunkEntity>) {
        chunks.forEach { entity ->
            val withId = if (entity.id == 0L) entity.copy(id = nextId++) else entity
            this.chunks.removeIf { it.id == withId.id }
            this.chunks.add(withId)
        }
    }

    override suspend fun deleteAll() {
        chunks.clear()
    }

    override suspend fun getAll(strategy: String): List<RagChunkEntity> =
        chunks.filter { it.strategy == strategy }.sortedBy { it.chunkId }

    override suspend fun countByStrategy(strategy: String): Int =
        chunks.count { it.strategy == strategy }

    override suspend fun countAll(): Int = chunks.size
}
