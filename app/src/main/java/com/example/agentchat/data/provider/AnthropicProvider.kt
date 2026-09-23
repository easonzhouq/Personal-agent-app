package com.example.agentchat.data.provider

import com.example.agentchat.domain.model.ChatError
import com.example.agentchat.domain.model.ChatEvent
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.provider.ModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Call.Factory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Anthropic Messages API provider with streaming content_block_delta support. */
class AnthropicProvider(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val callFactory: Factory = client,
) : ModelProvider {
    override fun stream(config: ModelConfig, apiKey: String, messages: List<ChatMessage>): Flow<ChatEvent> = flow {
        emit(ChatEvent.Started)
        try {
            if (messages.any { it.attachments.isNotEmpty() }) {
                emit(ChatEvent.Failed(ChatError("unsupported_attachment", "Anthropic 图片或文件消息暂未支持")))
                return@flow
            }
            if (!isAllowedBaseUrl(config.baseUrl)) {
                emit(ChatEvent.Failed(ChatError("invalid_url", "Provider URL 必须使用 HTTPS；请填写 Anthropic API 地址")))
                return@flow
            }
            val requestJson = buildJsonObject {
                put("model", config.modelName)
                put("max_tokens", MAX_TOKENS)
                put("stream", true)
                putJsonArray("messages") {
                    messages.filter { it.role.name.equals("USER", true) || it.role.name.equals("ASSISTANT", true) }
                        .forEach { message ->
                            add(buildJsonObject {
                                put("role", message.role.name.lowercase())
                                put("content", message.text)
                            })
                        }
                }
            }
            val request = Request.Builder()
                .url(messagesEndpoint(config.baseUrl))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("Accept", "text/event-stream")
                .post(json.encodeToString(JsonObject.serializer(), requestJson).toRequestBody("application/json".toMediaType()))
                .build()

            val response = executeCancellable(request)
            val cancellationHandle = currentCoroutineContext()[Job]!!.invokeOnCompletion { response.call.cancel() }
            try {
                response.response.use { httpResponse ->
                    if (!httpResponse.isSuccessful) {
                        emit(ChatEvent.Failed(ProviderErrorMapper.http(httpResponse.code)))
                        return@flow
                    }
                    val body = httpResponse.body
                    if (body == null) {
                        emit(ChatEvent.Failed(ChatError("empty_response", "Provider returned an empty response")))
                        return@flow
                    }
                    parseEvents(body.source()).collect { emit(it) }
                }
            } finally {
                cancellationHandle.dispose()
                response.call.cancel()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            currentCoroutineContext().ensureActive()
            emit(ChatEvent.Failed(ChatError("timeout", "Provider connection timed out", retryable = true)))
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            emit(ChatEvent.Failed(ChatError("network_error", "Provider connection failed", retryable = true)))
        } catch (invalidUrl: IllegalArgumentException) {
            currentCoroutineContext().ensureActive()
            emit(ChatEvent.Failed(ChatError("invalid_url", "Provider URL is invalid")))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            emit(ChatEvent.Failed(ChatError("provider_error", "Provider request failed")))
        }
    }.flowOn(Dispatchers.IO)

    private data class CancellableResponse(val call: Call, val response: okhttp3.Response)

    private suspend fun executeCancellable(request: Request): CancellableResponse = suspendCancellableCoroutine { continuation ->
        val call = callFactory.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) = continuation.resumeWithException(error)
            override fun onResponse(call: Call, response: okhttp3.Response) = continuation.resume(CancellableResponse(call, response))
        })
    }

    private fun parseEvents(source: okio.BufferedSource): Flow<ChatEvent> = flow {
        var eventType: String? = null
        var terminal = false
        while (!source.exhausted() && !terminal) {
            val line = source.readUtf8Line() ?: break
            when {
                line.startsWith("event:") -> eventType = line.removePrefix("event:").trim()
                !line.startsWith("data:") -> Unit
                else -> {
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    val event = try {
                        json.parseToJsonElement(payload).jsonObject
                    } catch (_: Exception) {
                        emit(ChatEvent.Failed(ChatError("malformed_json", "Malformed provider event")))
                        terminal = true
                        continue
                    }
                    val type = event["type"]?.jsonPrimitive?.contentOrNull ?: eventType
                    when (type) {
                        "content_block_delta" -> event["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { emit(ChatEvent.Delta(it)) }
                        "message_stop" -> {
                            emit(ChatEvent.Completed())
                            terminal = true
                        }
                        "error" -> {
                            val error = event["error"]?.jsonObject
                            emit(ChatEvent.Failed(mapAnthropicError(error?.get("type")?.jsonPrimitive?.contentOrNull, error?.get("message")?.jsonPrimitive?.contentOrNull)))
                            terminal = true
                        }
                    }
                    eventType = null
                }
            }
        }
        if (!terminal) emit(ChatEvent.Failed(ChatError("incomplete_stream", "Provider stream ended before message_stop")))
    }

    private fun mapAnthropicError(type: String?, message: String?): ChatError = when (type) {
        "authentication_error" -> ChatError("unauthorized", message ?: "Anthropic authentication failed")
        "rate_limit_error" -> ChatError("rate_limited", message ?: "Anthropic is temporarily unavailable", retryable = true)
        "overloaded_error" -> ChatError("provider_unavailable", message ?: "Anthropic is temporarily unavailable", retryable = true)
        else -> ChatError("provider_error", message ?: "Anthropic request failed")
    }

    companion object {
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val MAX_TOKENS = 4096

        fun isAllowedBaseUrl(value: String): Boolean = runCatching {
            val uri = java.net.URI(value)
            uri.scheme.equals("https", true) || (uri.scheme.equals("http", true) && uri.host in setOf("localhost", "127.0.0.1", "::1"))
        }.getOrDefault(false)

        fun messagesEndpoint(baseUrl: String): String {
            val normalized = baseUrl.trimEnd('/')
            return when {
                normalized.endsWith("/messages") -> normalized
                normalized.endsWith("/v1") -> "$normalized/messages"
                else -> "$normalized/v1/messages"
            }
        }
    }
}
