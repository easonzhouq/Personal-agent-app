package com.example.agentchat.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["messageId"])],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentUri: String,
)
