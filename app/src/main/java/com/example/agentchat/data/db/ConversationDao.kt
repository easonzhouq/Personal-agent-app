package com.example.agentchat.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query(
        "SELECT DISTINCT c.* FROM conversations AS c " +
            "LEFT JOIN messages AS m ON m.conversationId = c.id " +
            "WHERE c.title LIKE '%' || :query || '%' " +
            "OR m.text LIKE '%' || :query || '%' " +
            "ORDER BY c.updatedAt DESC",
    )
    suspend fun search(query: String): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitleAndTimestamp(id: String, title: String, updatedAt: Long): Int

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ConversationEntity?

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}
