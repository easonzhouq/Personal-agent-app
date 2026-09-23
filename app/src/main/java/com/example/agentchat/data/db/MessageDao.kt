package com.example.agentchat.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Transaction
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeWithAttachments(conversationId: String): Flow<List<MessageWithAttachments>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE status = 'COMPLETED' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun findRecentCompleted(limit: Int): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: MessageEntity)

    @Query("UPDATE messages SET text = :text, status = :status WHERE id = :id")
    suspend fun updateTextAndStatus(id: String, text: String, status: String): Int

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

data class MessageWithAttachments(
    @Embedded val message: MessageEntity,
    @Relation(parentColumn = "id", entityColumn = "messageId")
    val attachments: List<AttachmentEntity>,
)
