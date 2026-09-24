package com.example.agentchat.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ProviderProtocol {
    @SerialName("openai_compatible")
    OPENAI_COMPATIBLE,

    @SerialName("anthropic")
    ANTHROPIC,

    @SerialName("gemini")
    GEMINI,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
data class ModelConfig(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val modelName: String,
    val protocol: ProviderProtocol,
    val enabled: Boolean = true,
    val supportsVision: Boolean = false,
    val supportsFiles: Boolean = false,
    val isDefault: Boolean = false,
    val profilePrompt: String = "",
)
