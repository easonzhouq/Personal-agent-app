package com.example.agentchat.data.rag

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "knowledge_sources")
data class KnowledgeSourceEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val contentUri: String,
    val importedAt: Long,
    val chunkCount: Int = 0,
)

@Entity(
    tableName = "knowledge_chunks",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sourceId"]), Index(value = ["sourceId", "chunkIndex"], unique = true)],
)
data class KnowledgeChunkEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val sourceName: String,
    val chunkIndex: Int,
    val text: String,
    val createdAt: Long,
)
