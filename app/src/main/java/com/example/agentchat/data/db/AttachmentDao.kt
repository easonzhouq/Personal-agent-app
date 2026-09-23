package com.example.agentchat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE messageId IN (:messageIds)")
    suspend fun findForMessages(messageIds: List<String>): List<AttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(attachments: List<AttachmentEntity>)

    @Query("SELECT COUNT(*) FROM attachments")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM attachments WHERE contentUri = :contentUri")
    suspend fun countByContentUri(contentUri: String): Int

    @Query("SELECT DISTINCT contentUri FROM attachments")
    suspend fun findAllContentUris(): List<String>

    @Query("DELETE FROM attachments")
    suspend fun deleteAll()
}
