package com.example.agentchat.ui.history

import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.agentchat.data.secret.SecretStore

class HistoryViewModelTest {
    @Test
    fun deleteConfirmationPassesCurrentDraftUrisDirectlyToRepository() = runTest {
        val repository = HistoryViewModelFakeRepository(listOf(Conversation("c", "title", 1L, 1L)))
        val viewModel = HistoryViewModel(repository)

        viewModel.requestDelete("c")
        viewModel.confirmDelete(setOf("content://draft"))

        assertEquals(setOf("content://draft"), repository.deletedDraftUris)
    }

    @Test
    fun missingExportConversationProducesExplicitResult() = runTest {
        val viewModel = HistoryViewModel(HistoryViewModelFakeRepository(emptyList()))
        viewModel.exportConversation("missing", fakeResolver(), android.net.Uri.parse("content://destination"), emptySet())

        assertEquals(ExportResult.ConversationNotFound, viewModel.uiState.value.exportResult)
    }

    @Test
    fun cancelledExportProducesExplicitResult() {
        val viewModel = HistoryViewModel(HistoryViewModelFakeRepository(emptyList()))
        viewModel.reportExportCancelled()
        assertTrue(viewModel.uiState.value.exportResult is ExportResult.Cancelled)
    }

    @Test
    fun staleSearchResultCannotOverwriteNewerQuery() = runTest {
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldConversation = Conversation("old", "old result", 1L, 1L)
        val newConversation = Conversation("new", "new result", 1L, 1L)
        val repository = object : HistoryRepositoryPort {
            override fun observeConversations(): Flow<List<Conversation>> = MutableStateFlow(emptyList())
            override fun observeMessages(id: String): Flow<List<ChatMessage>> = emptyFlow()
            override suspend fun search(query: String): List<Conversation> {
                if (query == "old") { oldStarted.complete(Unit); releaseOld.await(); return listOf(oldConversation) }
                return listOf(newConversation)
            }
            override suspend fun deleteConversation(id: String, draftContentUris: Set<String>) = Unit
        }
        val viewModel = HistoryViewModel(repository)

        viewModel.updateQuery("old")
        oldStarted.await()
        viewModel.updateQuery("new")
        advanceUntilIdle()
        assertEquals(listOf(newConversation), viewModel.uiState.value.conversations)
        releaseOld.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(newConversation), viewModel.uiState.value.conversations)
    }

    @Test
    fun secretStoreReadFailureIsVisibleAsExportError() = runTest {
        val viewModel = HistoryViewModel(HistoryViewModelFakeRepository(emptyList()))
        val failingStore = object : SecretStore {
            override suspend fun putApiKey(configId: String, apiKey: String) = Unit
            override suspend fun getApiKey(configId: String): String? = error("decrypt failed")
            override suspend fun deleteApiKey(configId: String) = Unit
            override suspend fun clearAllApiKeys() = Unit
            override suspend fun snapshotApiKeys() = com.example.agentchat.data.secret.ApiKeySnapshot(emptyMap())
            override suspend fun restoreApiKeys(snapshot: com.example.agentchat.data.secret.ApiKeySnapshot) = Unit
        }

        viewModel.exportConversationWithSecrets("missing", fakeResolver(), android.net.Uri.parse("content://destination"), failingStore, listOf("config"))

        assertEquals(ExportResult.SecretReadFailed, viewModel.uiState.value.exportResult)
    }

    @Test
    fun markdownWriteFailureBecomesWriteFailedResult() = runTest {
        val conversation = Conversation("c", "title", 1L, 1L)
        val repository = HistoryViewModelFakeRepository(listOf(conversation), listOf(ChatMessage("m", "c", com.example.agentchat.domain.model.Role.USER, "hello", status = com.example.agentchat.domain.model.MessageStatus.COMPLETED)))
        val viewModel = HistoryViewModel(repository, writeMarkdown = { _, _, _, _, _ -> error("disk full") })

        viewModel.exportConversation("c", fakeResolver(), android.net.Uri.parse("content://destination"), emptySet())

        assertEquals(ExportResult.WriteFailed, viewModel.uiState.value.exportResult)
    }

    @Test
    fun createDocumentSuccessPathProducesSuccessResult() = runTest {
        val repository = HistoryViewModelFakeRepository(listOf(Conversation("c", "title", 1L, 1L)), listOf(ChatMessage("m", "c", com.example.agentchat.domain.model.Role.USER, "hello", status = com.example.agentchat.domain.model.MessageStatus.COMPLETED)))
        val store = object : SecretStore {
            override suspend fun putApiKey(configId: String, apiKey: String) = Unit
            override suspend fun getApiKey(configId: String): String? = "secret"
            override suspend fun deleteApiKey(configId: String) = Unit
            override suspend fun clearAllApiKeys() = Unit
            override suspend fun snapshotApiKeys() = com.example.agentchat.data.secret.ApiKeySnapshot(emptyMap())
            override suspend fun restoreApiKeys(snapshot: com.example.agentchat.data.secret.ApiKeySnapshot) = Unit
        }
        val viewModel = HistoryViewModel(repository, writeMarkdown = { _, _, _, _, _ -> Unit })

        viewModel.exportConversationWithSecrets("c", fakeResolver(), android.net.Uri.parse("content://destination"), store, listOf("config"))

        assertEquals(ExportResult.Success, viewModel.uiState.value.exportResult)
    }

    @Test
    fun searchFailureIsVisibleAndSearchingStateIsCleared() = runTest {
        val repository = HistoryViewModelFakeRepository(emptyList()).apply { searchError = IllegalStateException("search failed") }
        val viewModel = HistoryViewModel(repository)

        viewModel.updateQuery("broken")
        advanceUntilIdle()

        assertEquals("search failed", viewModel.uiState.value.error)
        assertTrue(!viewModel.uiState.value.isSearching)
    }

    private fun fakeResolver(): android.content.ContentResolver = object : android.content.ContentResolver(null) {}
}

private class HistoryViewModelFakeRepository(initial: List<Conversation>, private val messages: List<ChatMessage> = emptyList()) : HistoryRepositoryPort {
    private val conversations = MutableStateFlow(initial)
    var deletedDraftUris: Set<String> = emptySet()
    var searchError: Throwable? = null
    override fun observeConversations(): Flow<List<Conversation>> = conversations
    override fun observeMessages(id: String): Flow<List<ChatMessage>> = flowOf(messages)
    override suspend fun search(query: String): List<Conversation> = searchError?.let { throw it } ?: conversations.value.filter { it.title.contains(query, true) }
    override suspend fun deleteConversation(id: String, draftContentUris: Set<String>) { deletedDraftUris = draftContentUris }
}
