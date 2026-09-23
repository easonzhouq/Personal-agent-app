package com.example.agentchat.data.provider

import android.content.ContentResolver
import android.content.Context
import com.example.agentchat.data.attachment.AttachmentReadError
import com.example.agentchat.data.attachment.ContentResolverAttachmentEncoder
import com.example.agentchat.data.attachment.UnsupportedAttachment
import com.example.agentchat.domain.model.ChatError
import com.example.agentchat.domain.model.ChatEvent
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.provider.ModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenAiCompatibleProvider(
    private val client: OkHttpClient = OkHttpClient(),
    private val attachmentEncoder: AttachmentEncoder? = null,
    contentResolver: ContentResolver? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val callFactory: Call.Factory = client,
) : ModelProvider {
    private val effectiveAttachmentEncoder = attachmentEncoder ?: contentResolver?.let { ContentResolverAttachmentEncoder(it) }

    override fun stream(config: ModelConfig, apiKey: String, messages: List<ChatMessage>): Flow<ChatEvent> = flow {
        emit(ChatEvent.Started)
        try {
            if (messages.any { it.attachments.isNotEmpty() && effectiveAttachmentEncoder == null }) {
                emit(ChatEvent.Failed(ChatError("unsupported_attachment", "Attachments are not supported by this provider")))
                return@flow
            }
            val encodedMessages = messages.map { message ->
                buildJsonObject {
                    put("role", message.role.name.lowercase())
                    put("content", encodeContent(message))
                }
            }
            val requestJson = buildJsonObject {
                put("model", config.modelName)
                put("stream", true)
                put("messages", JsonArray(encodedMessages))
            }
            if (!isAllowedBaseUrl(config.baseUrl)) {
                emit(ChatEvent.Failed(ChatError("invalid_url", "Provider URL must use HTTPS or localhost HTTP")))
                return@flow
            }
            val request = Request.Builder()
                .url(chatCompletionsEndpoint(config.baseUrl))
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "text/event-stream")
                .post(json.encodeToString(JsonObject.serializer(), requestJson).toRequestBody("application/json".toMediaType()))
                .build()

            val cancellableResponse = executeCancellable(request)
            val cancellationHandle = currentCoroutineContext()[Job]!!.invokeOnCompletion { cancellableResponse.call.cancel() }
            try {
                cancellableResponse.response.use { response ->
                    if (!response.isSuccessful) {
                        emit(ChatEvent.Failed(ProviderErrorMapper.http(response.code)))
                        return@flow
                    }
                    val body = response.body
                    if (body == null) {
                        emit(ChatEvent.Failed(ChatError("empty_response", "Provider returned an empty response")))
                        return@flow
                    }
                    SseChatParser(json).parse(body.source()).collect { emit(it) }
                }
            } finally {
                cancellationHandle.dispose()
                cancellableResponse.call.cancel()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AttachmentReadError) {
            currentCoroutineContext().ensureActive()
            emit(ChatEvent.Failed(ChatError("attachment_read_error", "Unable to read attachment")))
        } catch (error: UnsupportedAttachment) {
            currentCoroutineContext().ensureActive()
            emit(ChatEvent.Failed(ChatError("unsupported_attachment", "Attachment is not supported")))
        } catch (invalidUrl: IllegalArgumentException) {
            currentCoroutineContext().ensureActive()
            emit(ChatEvent.Failed(ChatError("invalid_url", "Provider URL is invalid")))
        } catch (_: SocketTimeoutException) {
            currentCoroutineContext().ensureActive()
            emit(ChatEvent.Failed(ChatError("timeout", "Provider connection timed out", retryable = true)))
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            emit(ChatEvent.Failed(ChatError("network_error", "Provider connection failed", retryable = true)))
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            emit(ChatEvent.Failed(ChatError("provider_error", "Provider request failed")))
        }
    }.flowOn(Dispatchers.IO)

    private data class CancellableResponse(val call: Call, val response: Response)

    private suspend fun executeCancellable(request: Request): CancellableResponse = suspendCancellableCoroutine { continuation ->
        val call = callFactory.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(CancellableResponse(call, response))
            }
        })
    }

    private fun encodeContent(message: ChatMessage) = if (message.attachments.isEmpty()) {
        JsonPrimitive(message.text)
    } else {
        JsonArray(buildList {
            add(buildJsonObject {
                put("type", "text")
                put("text", message.text)
            })
            message.attachments.forEach { add(effectiveAttachmentEncoder!!.encode(it)) }
        })
    }

    companion object {
        fun isAllowedBaseUrl(value: String): Boolean = runCatching {
            val uri = java.net.URI(value)
            uri.scheme.equals("https", true) || (uri.scheme.equals("http", true) && uri.host in setOf("localhost", "127.0.0.1", "::1"))
        }.getOrDefault(false)

        fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trimEnd('/')
        fun chatCompletionsEndpoint(baseUrl: String): String {
            val normalized = normalizeBaseUrl(baseUrl)
            return if (normalized.endsWith("/chat/completions")) normalized else "$normalized/chat/completions"
        }
        fun production(context: Context, client: OkHttpClient = OkHttpClient()): OpenAiCompatibleProvider =
            OpenAiCompatibleProvider(client = client, contentResolver = context.applicationContext.contentResolver)
    }
}
