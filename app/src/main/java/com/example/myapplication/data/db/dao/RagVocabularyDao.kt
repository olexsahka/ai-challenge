package com.example.myapplication.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.db.entity.RagVocabularyEntity

@Dao
interface RagVocabularyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(vocab: List<RagVocabularyEntity>)

    @Query("DELETE FROM rag_vocabulary")
    suspend fun deleteAll()

    @Query("SELECT * FROM rag_vocabulary WHERE strategy = :strategy")
    suspend fun getAll(strategy: String): List<RagVocabularyEntity>
}
