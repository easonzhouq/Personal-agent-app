package com.example.agentchat.ui.chat

import com.example.agentchat.domain.model.Attachment
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.data.attachment.AttachmentRejection
import java.util.UUID

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val attachments: List<Attachment> = emptyList(),
    val isStreaming: Boolean = false,
    val error: String? = null,
    val selectedModel: ModelConfig? = null,
    val selectedConfigId: String? = null,
    val conversationId: String = UUID.randomUUID().toString(),
)

sealed interface ChatIntent {
    data class DraftChanged(val value: String) : ChatIntent
    data class AttachmentsSelected(val attachments: List<Attachment>, val rejected: List<AttachmentRejection> = emptyList()) : ChatIntent
    data object Send : ChatIntent
    data object Stop : ChatIntent
    data object Retry : ChatIntent
    data class RetryAssistant(val assistantId: String) : ChatIntent
    data class RemoveAttachment(val attachmentId: String) : ChatIntent
}
