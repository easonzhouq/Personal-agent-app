package com.example.agentchat.data.export

import com.example.agentchat.domain.model.Attachment
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.MessageStatus
import com.example.agentchat.domain.model.Role
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.agentchat.data.secret.SecretStore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows

class MarkdownExporterTest {
    @Test
    fun secretReadFailureIsReturnedInsteadOfThrown() = runTest {
        val store = object : SecretStore {
            override suspend fun putApiKey(configId: String, apiKey: String) = Unit
            override suspend fun getApiKey(configId: String): String? = error("decrypt failed")
            override suspend fun deleteApiKey(configId: String) = Unit
            override suspend fun clearAllApiKeys() = Unit
            override suspend fun snapshotApiKeys() = com.example.agentchat.data.secret.ApiKeySnapshot(emptyMap())
            override suspend fun restoreApiKeys(snapshot: com.example.agentchat.data.secret.ApiKeySnapshot) = Unit
        }

        assertTrue(MarkdownExporter.readSecrets(store, listOf("config")).isFailed())
    }

    @Test
    fun overlappingSecretsReplaceLongKeyBeforeItsShortPrefix() {
        val markdown = MarkdownExporter.render("title", listOf(message("long-key")), secretValues = setOf("key", "long-key"))

        assertTrue(markdown.contains("[REDACTED]"))
        assertFalse(markdown.contains("long-[REDACTED]"))
        assertFalse(markdown.contains("long-key"))
    }

    private fun message(text: String) = ChatMessage("m", "c", Role.USER, text, status = MessageStatus.COMPLETED)
    @Test
    fun exportsSafeConversationMetadataWithoutUriOrRawAttachmentContent() {
        val message = ChatMessage(
            id = "m1", conversationId = "c1", role = Role.USER,
            text = "hello", status = MessageStatus.COMPLETED,
            attachments = listOf(Attachment("a1", "secret.pdf", "application/pdf", 12, "content://private/raw")), createdAt = 5678L,
        )

        val markdown = MarkdownExporter.render("A title", listOf(message), timestamp = 1234L, secretValues = setOf("hello"))

        assertTrue(markdown.contains("# A title"))
        assertTrue(markdown.contains("USER"))
        assertTrue(markdown.contains("secret.pdf"))
        assertTrue(markdown.contains("application/pdf"))
        assertTrue(markdown.contains("[REDACTED]"))
        assertFalse(markdown.contains("content://private/raw"))
        assertFalse(markdown.contains("secret-api-key"))
    }

    @Test
    fun secretReadCancellationIsNotConvertedToFailureResult() = runTest {
        val cancellation = CancellationException("cancelled")
        val store = object : SecretStore {
            override suspend fun putApiKey(configId: String, apiKey: String) = Unit
            override suspend fun getApiKey(configId: String): String? = throw cancellation
            override suspend fun deleteApiKey(configId: String) = Unit
            override suspend fun clearAllApiKeys() = Unit
            override suspend fun snapshotApiKeys() = com.example.agentchat.data.secret.ApiKeySnapshot(emptyMap())
            override suspend fun restoreApiKeys(snapshot: com.example.agentchat.data.secret.ApiKeySnapshot) = Unit
        }

        assertThrows(CancellationException::class.java) { kotlinx.coroutines.runBlocking { MarkdownExporter.readSecrets(store, listOf("config")) } }
    }
}

private fun SecretReadResult.isFailed() = this is SecretReadResult.Failed
