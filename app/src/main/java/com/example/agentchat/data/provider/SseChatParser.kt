package com.example.agentchat.data.provider

import okio.BufferedSource
import com.example.agentchat.domain.model.ChatEvent
import com.example.agentchat.domain.model.ChatError
import com.example.agentchat.domain.model.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull

class SseChatParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parse(source: BufferedSource): Flow<ChatEvent> = flow {
        var terminal = false
        var cachedUsage: Usage? = null
        while (!source.exhausted() && !terminal) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            if (payload == "[DONE]") {
                emit(ChatEvent.Completed(cachedUsage))
                terminal = true
                continue
            }
            val event = try {
                json.parseToJsonElement(payload).jsonObject
            } catch (_: Exception) {
                emit(ChatEvent.Failed(ChatError("malformed_json", "Malformed provider event")))
                terminal = true
                continue
            }

            try {
                event["error"]?.jsonObject?.let { error ->
                    emit(ChatEvent.Failed(ProviderErrorMapper.provider(error["code"]?.jsonPrimitive?.content)))
                    terminal = true
                }
                if (terminal) continue
                event["usage"]?.jsonObject?.let {
                    cachedUsage = Usage(
                        promptTokens = it["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                        completionTokens = it["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                        totalTokens = it["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    )
                }
                val content = event["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                if (!content.isNullOrEmpty()) emit(ChatEvent.Delta(content))
            } catch (_: Exception) {
                emit(ChatEvent.Failed(ChatError("malformed_json", "Malformed provider event")))
                terminal = true
            }
        }
        if (!terminal) emit(ChatEvent.Failed(ChatError("incomplete_stream", "Provider stream ended before [DONE]")))
    }
}
