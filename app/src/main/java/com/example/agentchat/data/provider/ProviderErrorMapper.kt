package com.example.agentchat.data.provider

import com.example.agentchat.domain.model.ChatError

object ProviderErrorMapper {
    fun http(statusCode: Int): ChatError = when (statusCode) {
        401, 403 -> ChatError("unauthorized", "Provider authentication failed")
        408, 425, 429 -> ChatError("rate_limited", "Provider is temporarily unavailable", retryable = true)
        in 500..599 -> ChatError("provider_unavailable", "Provider is temporarily unavailable", retryable = true)
        else -> ChatError("http_$statusCode", "Provider request failed")
    }

    fun provider(providerCode: String?): ChatError = when (providerCode) {
        "rate_limit_exceeded" -> ChatError("rate_limited", "Provider is temporarily unavailable", retryable = true)
        "service_unavailable" -> ChatError("provider_unavailable", "Provider is temporarily unavailable", retryable = true)
        "timeout" -> ChatError("timeout", "Provider connection timed out", retryable = true)
        "invalid_schema", "unsupported_protocol" -> ChatError("protocol_error", "Provider response is incompatible")
        else -> ChatError("provider_error", "Provider returned an error")
    }
}
