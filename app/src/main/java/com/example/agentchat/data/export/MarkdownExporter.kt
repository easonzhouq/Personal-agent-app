package com.example.agentchat.data.export

import android.content.ContentResolver
import android.net.Uri
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.data.secret.SecretStore
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date

object MarkdownExporter {
    suspend fun readSecrets(secretStore: SecretStore, configIds: Collection<String>): SecretReadResult = try {
        SecretReadResult.Success(configIds.mapNotNull { secretStore.getApiKey(it) }.filter { it.isNotBlank() }.toSet())
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        SecretReadResult.Failed
    }
    fun render(title: String, messages: List<ChatMessage>, timestamp: Long = System.currentTimeMillis(), secretValues: Set<String> = emptySet()): String = buildString {
        append("# ").append(redact(title.replace("\n", " "), secretValues)).append("\n\n")
        messages.forEach { message ->
            append("## ").append(message.role.name).append(" — ")
                .append(DateFormat.getDateTimeInstance().format(Date(message.createdAt.takeIf { it > 0 } ?: timestamp))).append("\n\n")
            append(redact(message.text, secretValues)).append("\n\n")
            message.attachments.forEach { attachment ->
                append("- 附件：").append(redact(attachment.name, secretValues)).append("（").append(attachment.mimeType).append("）\n")
            }
            if (message.attachments.isNotEmpty()) append("\n")
        }
    }

    fun export(resolver: ContentResolver, destination: Uri, title: String, messages: List<ChatMessage>, timestamp: Long = System.currentTimeMillis(), secretValues: Set<String> = emptySet()) {
        resolver.openOutputStream(destination)?.bufferedWriter()?.use { it.write(render(title, messages, timestamp, secretValues)) }
            ?: error("Unable to open export destination")
    }

    private fun redact(value: String, secretValues: Set<String>): String = secretValues
        .filter { it.isNotBlank() }
        .sortedByDescending { it.length }
        .fold(value) { text, secret -> text.replace(secret, "[REDACTED]") }
}

sealed class SecretReadResult {
    data class Success(val values: Set<String>) : SecretReadResult()
    data object Failed : SecretReadResult()
}
