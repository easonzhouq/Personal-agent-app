package com.example.agentchat.data.rag

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM knowledge_sources ORDER BY importedAt DESC")
    fun observeSources(): Flow<List<KnowledgeSourceEntity>>

    @Query("SELECT * FROM knowledge_chunks ORDER BY sourceId, chunkIndex")
    suspend fun findAllChunks(): List<KnowledgeChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: KnowledgeSourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<KnowledgeChunkEntity>)

    @Query("DELETE FROM knowledge_sources WHERE id = :sourceId")
    suspend fun deleteSource(sourceId: String)

    @Query("DELETE FROM knowledge_sources")
    suspend fun deleteAllSources()
}
