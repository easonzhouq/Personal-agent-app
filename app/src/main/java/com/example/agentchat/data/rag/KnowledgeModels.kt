package com.example.agentchat.data.rag

data class KnowledgeChunk(
    val id: String,
    val sourceId: String,
    val sourceName: String,
    val chunkIndex: Int,
    val text: String,
    val score: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

data class KnowledgeSource(
    val sourceId: String,
    val displayName: String,
    val contentUri: String,
    val chunkCount: Int,
    val importedAt: Long,
)
