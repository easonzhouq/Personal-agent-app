package com.example.agentchat.data.rag

import androidx.room.withTransaction
import com.example.agentchat.data.db.AgentDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class KnowledgeRepository(private val database: AgentDatabase) {
    private val dao = database.knowledgeDao()

    fun observeSources(): Flow<List<KnowledgeSource>> = dao.observeSources().map { sources ->
        sources.map { source ->
            KnowledgeSource(
                sourceId = source.id,
                displayName = source.displayName,
                contentUri = source.contentUri,
                chunkCount = source.chunkCount,
                importedAt = source.importedAt,
            )
        }
    }

    suspend fun importDocument(
        sourceId: String = UUID.randomUUID().toString(),
        displayName: String,
        contentUri: String,
        content: String,
    ): KnowledgeSource {
        val importedAt = System.currentTimeMillis()
        val chunks = KnowledgeChunker.chunk(sourceId, displayName, content).map { chunk ->
            KnowledgeChunkEntity(
                id = chunk.id,
                sourceId = chunk.sourceId,
                sourceName = chunk.sourceName,
                chunkIndex = chunk.chunkIndex,
                text = chunk.text,
                createdAt = importedAt,
            )
        }
        require(chunks.isNotEmpty()) { "文档没有可索引的文本" }
        database.withTransaction {
            dao.deleteSource(sourceId)
            dao.insertSource(KnowledgeSourceEntity(sourceId, displayName, contentUri, importedAt, chunks.size))
            dao.insertChunks(chunks)
        }
        return KnowledgeSource(sourceId, displayName, contentUri, chunks.size, importedAt)
    }

    suspend fun retrieveRelevant(query: String, limit: Int = 6): List<KnowledgeChunk> =
        KnowledgeChunker.rank(
            query,
            dao.findAllChunks().map { entity ->
                KnowledgeChunk(
                    id = entity.id,
                    sourceId = entity.sourceId,
                    sourceName = entity.sourceName,
                    chunkIndex = entity.chunkIndex,
                    text = entity.text,
                    createdAt = entity.createdAt,
                )
            },
            limit,
        )

    suspend fun deleteSource(sourceId: String) = dao.deleteSource(sourceId)

    suspend fun deleteAllSources() = dao.deleteAllSources()
}
