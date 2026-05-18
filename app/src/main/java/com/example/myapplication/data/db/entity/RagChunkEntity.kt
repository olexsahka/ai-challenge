package com.example.myapplication.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "rag_chunks",
    indices = [Index(value = ["strategy", "chunkId"])]
)
data class RagChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chunkId: Int,
    val source: String,
    val title: String,
    val section: String?,
    val text: String,
    val embeddingBlob: ByteArray?,
    val strategy: String,
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RagChunkEntity) return false
        return id == other.id && chunkId == other.chunkId && source == other.source &&
            title == other.title && section == other.section && text == other.text &&
            embeddingBlob.contentEquals(other.embeddingBlob) &&
            strategy == other.strategy && createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + chunkId
        result = 31 * result + source.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + (section?.hashCode() ?: 0)
        result = 31 * result + text.hashCode()
        result = 31 * result + (embeddingBlob?.contentHashCode() ?: 0)
        result = 31 * result + strategy.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

private fun ByteArray?.contentEquals(other: ByteArray?): Boolean =
    if (this == null && other == null) true
    else if (this == null || other == null) false
    else this.contentEquals(other)
