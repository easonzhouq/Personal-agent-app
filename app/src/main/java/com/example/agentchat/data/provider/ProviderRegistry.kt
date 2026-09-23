package com.example.agentchat.data.provider

import android.content.ContentResolver
import com.example.agentchat.domain.model.ChatEvent
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.model.ProviderProtocol
import com.example.agentchat.domain.provider.ModelProvider
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

class ProviderRegistry(
    private val client: OkHttpClient = OkHttpClient(),
    private val contentResolver: ContentResolver? = null,
    private val attachmentEncoder: AttachmentEncoder? = null,
    providerFactory: ((ModelConfig) -> ModelProvider?)? = null,
) {
    @Volatile
    private var providerFactory: ((ModelConfig) -> ModelProvider?)? = providerFactory

    /** Allows instrumentation tests to replace the network provider without changing production wiring. */
    fun setProviderFactory(factory: ((ModelConfig) -> ModelProvider?)?) {
        providerFactory = factory
    }

    fun providerFor(config: ModelConfig): ModelProvider? = providerFactory?.invoke(config) ?: when (config.protocol) {
        ProviderProtocol.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(client = client, attachmentEncoder = attachmentEncoder, contentResolver = contentResolver)
        else -> null
    }

    suspend fun testConnection(config: ModelConfig, apiKey: String): ConnectionResult {
        if (!config.enabled) return ConnectionResult.ProtocolIncompatible
        val provider = providerFor(config) ?: return ConnectionResult.ProtocolIncompatible
        val event = provider.stream(config, apiKey, listOf(ChatMessage("test", "test", com.example.agentchat.domain.model.Role.USER, "ping", status = com.example.agentchat.domain.model.MessageStatus.COMPLETED))).first { it is ChatEvent.Failed || it is ChatEvent.Completed }
        return when (event) {
            is ChatEvent.Completed -> ConnectionResult.Success
            is ChatEvent.Failed -> when (event.error.code) {
                "unauthorized" -> ConnectionResult.AuthenticationFailed
                "network_error" -> ConnectionResult.NetworkFailed
                "provider_unavailable", "rate_limited", "timeout" -> ConnectionResult.ServiceUnavailable
                "invalid_url" -> ConnectionResult.ProtocolIncompatible
                "unsupported_attachment", "protocol_error", "empty_response" -> ConnectionResult.ProtocolIncompatible
                "provider_error" -> ConnectionResult.NetworkFailed
                else -> ConnectionResult.NetworkFailed
            }
            else -> ConnectionResult.NetworkFailed
        }
    }
}

sealed class ConnectionResult {
    data object Success : ConnectionResult()
    data object AuthenticationFailed : ConnectionResult()
    data object NetworkFailed : ConnectionResult()
    data object ServiceUnavailable : ConnectionResult()
    data object ProtocolIncompatible : ConnectionResult()
    data object SecretReadFailed : ConnectionResult()
    data object ConnectionFailure : ConnectionResult()
}
