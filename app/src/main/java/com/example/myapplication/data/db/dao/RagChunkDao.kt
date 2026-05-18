package com.example.myapplication.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.db.entity.RagChunkEntity

@Dao
interface RagChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<RagChunkEntity>)

    @Query("DELETE FROM rag_chunks")
    suspend fun deleteAll()

    @Query("SELECT * FROM rag_chunks WHERE strategy = :strategy ORDER BY chunkId ASC")
    suspend fun getAll(strategy: String): List<RagChunkEntity>

    @Query("SELECT COUNT(*) FROM rag_chunks WHERE strategy = :strategy")
    suspend fun countByStrategy(strategy: String): Int

    @Query("SELECT COUNT(*) FROM rag_chunks")
    suspend fun countAll(): Int
}
