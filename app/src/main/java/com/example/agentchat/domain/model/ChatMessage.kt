package com.example.agentchat.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: Role,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val status: MessageStatus,
    val createdAt: Long = 0L,
)

@Serializable
enum class Role {
    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT,

    @SerialName("system")
    SYSTEM,
}

@Serializable
enum class MessageStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("streaming")
    STREAMING,

    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,

    @SerialName("cancelled")
    CANCELLED,
}
