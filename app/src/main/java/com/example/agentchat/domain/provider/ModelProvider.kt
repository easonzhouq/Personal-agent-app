package com.example.agentchat.domain.provider

import com.example.agentchat.domain.model.ChatEvent
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.ModelConfig
import kotlinx.coroutines.flow.Flow

interface ModelProvider {
    fun stream(
        config: ModelConfig,
        apiKey: String,
        messages: List<ChatMessage>,
    ): Flow<ChatEvent>
}
