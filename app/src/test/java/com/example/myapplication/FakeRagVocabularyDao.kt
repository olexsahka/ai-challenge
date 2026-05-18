package com.example.myapplication

import com.example.myapplication.data.db.dao.RagVocabularyDao
import com.example.myapplication.data.db.entity.RagVocabularyEntity

class FakeRagVocabularyDao : RagVocabularyDao {
    val vocab = mutableListOf<RagVocabularyEntity>()
    private var nextId = 1L

    override suspend fun insertAll(vocab: List<RagVocabularyEntity>) {
        vocab.forEach { entity ->
            val withId = if (entity.id == 0L) entity.copy(id = nextId++) else entity
            this.vocab.removeIf { it.id == withId.id }
            this.vocab.add(withId)
        }
    }

    override suspend fun deleteAll() {
        vocab.clear()
    }

    override suspend fun getAll(strategy: String): List<RagVocabularyEntity> =
        vocab.filter { it.strategy == strategy }
}
