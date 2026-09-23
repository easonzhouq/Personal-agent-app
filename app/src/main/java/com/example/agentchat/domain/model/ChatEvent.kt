package com.example.agentchat.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ChatEvent {
    @Serializable
    @SerialName("started")
    data object Started : ChatEvent

    @Serializable
    @SerialName("delta")
    data class Delta(val text: String) : ChatEvent

    @Serializable
    @SerialName("completed")
    data class Completed(val usage: Usage? = null) : ChatEvent

    @Serializable
    @SerialName("failed")
    data class Failed(val error: ChatError) : ChatEvent

    @Serializable
    @SerialName("cancelled")
    data object Cancelled : ChatEvent
}

@Serializable
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
)

@Serializable
data class ChatError(
    val code: String? = null,
    val message: String,
    val retryable: Boolean = false,
)
