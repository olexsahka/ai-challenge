package com.example.myapplication.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "facts",
    primaryKeys = ["sessionId", "factKey"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class FactEntity(
    val sessionId: String,
    val factKey: String,
    val factValue: String,
    val updatedAt: Long = System.currentTimeMillis()
)
