package com.example.myapplication.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val title: String,
    val systemPrompt: String = "",
    val model: String = "gpt-4o-mini",
    val temperature: Float = 1.0f,
    val compressionEnabled: Boolean = false,
    val compressionN: Int = 5,
    val compressionM: Int = 6
)
