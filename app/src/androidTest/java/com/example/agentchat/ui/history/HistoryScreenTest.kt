package com.example.agentchat.ui.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.Conversation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun busyConversationDisablesActionsAndSearchShowsLoading() {
        val gate = CompletableDeferred<Unit>()
        val repository = HistoryScreenFakeRepository(gate)
        val viewModel = HistoryViewModel(repository)
        viewModel.openConversation("conversation") { _, _ -> }
        viewModel.updateQuery("search")

        compose.setContent { HistoryScreen(viewModel, currentDraftUris = { emptySet() }, onOpen = {}, onExport = {}) }

        compose.onNodeWithContentDescription("打开会话 Title").assertIsNotEnabled()
        compose.onNodeWithText("搜索中…").assertIsDisplayed()
        gate.complete(Unit)
    }
}

private class HistoryScreenFakeRepository(private val gate: CompletableDeferred<Unit>) : HistoryRepositoryPort {
    override fun observeConversations(): Flow<List<Conversation>> = flowOf(Conversation("conversation", "Title", 1L, 1L).let(::listOf))
    override fun observeMessages(id: String): Flow<List<ChatMessage>> = flow { gate.await(); emit(emptyList()) }
    override suspend fun search(query: String): List<Conversation> { gate.await(); return emptyList() }
    override suspend fun deleteConversation(id: String, draftContentUris: Set<String>) = Unit
}
