package com.example.myapplication.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rag_vocabulary")
data class RagVocabularyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val dimensionIndex: Int,
    val idf: Float,
    val strategy: String
)
