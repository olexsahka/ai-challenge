package com.example.myapplication.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "summaries",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId", unique = true)]
)
data class SummaryEntity(
    @PrimaryKey val sessionId: String,
    val summary: String,
    val coveredMessageCount: Int,
    val updatedAt: Long = System.currentTimeMillis()
)
