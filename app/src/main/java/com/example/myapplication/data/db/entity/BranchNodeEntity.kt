package com.example.myapplication.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "branch_nodes",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId"), Index("parentId")]
)
data class BranchNodeEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val parentId: String? = null,
    val label: String,
    val createdAt: Long = System.currentTimeMillis()
)
