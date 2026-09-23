package com.example.agentchat.data.export

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agentchat.data.secret.SecretStore
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.Conversation
import com.example.agentchat.domain.model.MessageStatus
import com.example.agentchat.domain.model.Role
import com.example.agentchat.ui.history.ExportResult
import com.example.agentchat.ui.history.HistoryRepositoryPort
import com.example.agentchat.ui.history.HistoryViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportControllerTest {
    @get:Rule val compose = createComposeRule()
    private val destination = Uri.parse("content://picked/export.md")
    private val resolver = object : ContentResolver(null) {}
    private val secrets = object : SecretStore {
        override suspend fun putApiKey(configId: String, apiKey: String) = Unit
        override suspend fun getApiKey(configId: String): String? = if (configId == "cfg") "top-secret" else null
        override suspend fun deleteApiKey(configId: String) = Unit
        override suspend fun clearAllApiKeys() = Unit
        override suspend fun snapshotApiKeys() = com.example.agentchat.data.secret.ApiKeySnapshot(emptyMap())
        override suspend fun restoreApiKeys(snapshot: com.example.agentchat.data.secret.ApiKeySnapshot) = Unit
    }

    @Test
    fun createDocumentCallbackPassesUriConversationAndRedactionSecretsToWriter() {
        val received = mutableListOf<Any>()
        val viewModel = HistoryViewModel(repository(), writeMarkdown = { _, uri, title, messages, secretValues ->
            received.add(uri)
            received.add(title)
            received.add(messages.single().text)
            received.add(secretValues)
        })
        val controller = ExportController(viewModel, resolver, secrets) { listOf("cfg") }
        controller.requestDocument("conversation")
        compose.setContent { Button(onClick = { controller.onCreateDocumentResult(destination) }) { Text("deliver uri") } }

        compose.onNodeWithText("deliver uri").performClick()
        compose.waitForIdle()

        assertEquals(destination, received[0])
        assertEquals("Conversation", received[1])
        assertEquals("message top-secret", received[2])
        assertEquals(setOf("top-secret"), received[3])
        assertEquals(ExportResult.Success, viewModel.uiState.value.exportResult)
    }

    @Test
    fun cancelledCreateDocumentCallbackProducesCancelledResult() {
        val viewModel = HistoryViewModel(repository())
        val controller = ExportController(viewModel, resolver, secrets) { listOf("cfg") }
        controller.requestDocument("conversation")
        compose.setContent { Button(onClick = { controller.onCreateDocumentResult(null) }) { Text("cancel uri") } }

        compose.onNodeWithText("cancel uri").performClick()
        compose.waitForIdle()

        assertEquals(ExportResult.Cancelled, viewModel.uiState.value.exportResult)
    }

    @Test
    fun callbackWriterFailureProducesWriteFailedResult() {
        val viewModel = HistoryViewModel(repository(), writeMarkdown = { _, _, _, _, _ -> error("disk full") })
        val controller = ExportController(viewModel, resolver, secrets) { listOf("cfg") }
        controller.requestDocument("conversation")
        compose.setContent { Button(onClick = { controller.onCreateDocumentResult(destination) }) { Text("fail uri") } }

        compose.onNodeWithText("fail uri").performClick()
        compose.waitForIdle()

        assertEquals(ExportResult.WriteFailed, viewModel.uiState.value.exportResult)
    }

    private fun repository() = FakeRepository(
        Conversation("conversation", "Conversation", 1L, 1L),
        ChatMessage("message", "conversation", Role.USER, "message top-secret", status = MessageStatus.COMPLETED),
    )
}

private class FakeRepository(conversation: Conversation, private val message: ChatMessage) : HistoryRepositoryPort {
    private val values = MutableStateFlow(listOf(conversation))
    override fun observeConversations(): Flow<List<Conversation>> = values
    override fun observeMessages(id: String): Flow<List<ChatMessage>> = flowOf(listOf(message))
    override suspend fun search(query: String): List<Conversation> = values.value
    override suspend fun deleteConversation(id: String, draftContentUris: Set<String>) = Unit
}
