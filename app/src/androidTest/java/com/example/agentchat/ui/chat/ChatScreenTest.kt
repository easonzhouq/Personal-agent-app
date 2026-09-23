package com.example.agentchat.ui.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.Attachment
import com.example.agentchat.domain.model.MessageStatus
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.model.ProviderProtocol
import com.example.agentchat.domain.model.Role
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class ChatScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun emptyDraftUsesKeyboardSendWithoutSendButton() {
        compose.setContent { ChatScreenContent(ChatUiState(), {}) }
        compose.onAllNodesWithContentDescription("发送消息").assertCountEquals(0)
        compose.onNodeWithTag("chat-input").assertIsDisplayed()
    }

    @Test
    fun streamingShowsStopAndMessageOrder() {
        val messages = listOf(message("u", Role.USER, "first"), message("a", Role.ASSISTANT, "answer", MessageStatus.STREAMING))
        compose.setContent { ChatScreenContent(ChatUiState(messages = messages, isStreaming = true), {}) }
        compose.onNodeWithContentDescription("停止生成").assertIsDisplayed()
        compose.onNodeWithText("first").assertIsDisplayed()
        compose.onNodeWithText("answer").assertIsDisplayed()
    }

    @Test
    fun failedMessageOffersRetry() {
        compose.setContent {
            ChatScreenContent(
                ChatUiState(messages = listOf(message("a", Role.ASSISTANT, "failed", MessageStatus.FAILED))),
                onIntent = {},
            )
        }
        compose.onNodeWithText("重试").assertIsDisplayed()
    }

    @Test
    fun markdownCodeBlockHasDistinctLabel() {
        compose.setContent {
            ChatScreenContent(
                ChatUiState(messages = listOf(message("a", Role.ASSISTANT, "# Title\n- item\n```kotlin\nval x = 1\n```"))),
                onIntent = {},
            )
        }
        compose.onNodeWithText("Title").assertIsDisplayed()
        compose.onNodeWithText("item").assertIsDisplayed()
        compose.onNodeWithText("代码块").assertIsDisplayed()
        compose.onNodeWithText("val x = 1").assertIsDisplayed()
    }

    @Test
    fun composerShowsAttachmentAndVoicePlaceholders() {
        compose.setContent {
            Composer(
                draft = "",
                isStreaming = false,
                attachments = listOf(Attachment("a", "notes.txt", "text/plain", 4, "content://notes")),
                onDraftChanged = {},
                onSend = {},
            )
        }
        compose.onNodeWithContentDescription("添加附件").assertIsDisplayed()
        compose.onNodeWithContentDescription("语音输入，按住说话").assertIsDisplayed()
        compose.onNodeWithText("notes.txt").assertIsDisplayed()
    }

    @Test
    fun voiceInputShowsTranscribeAndCancelOverlay() {
        compose.setContent {
            Composer(
                draft = "",
                isStreaming = false,
                onDraftChanged = {},
                onSend = {},
                voiceInputState = com.example.agentchat.data.voice.VoiceInputState.LISTENING,
            )
        }
        compose.onNodeWithText("转文字").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
    }

    @Test
    fun topLeftModelCapsuleOpensModelSwitcher() {
        var selected: ModelConfig? = null
        val model = ModelConfig(
            id = "model-1",
            displayName = "GPT-4o",
            baseUrl = "https://example.com/v1",
            modelName = "gpt-4o",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            supportsVision = true,
        )

        compose.setContent {
            ChatScreenContent(
                state = ChatUiState(selectedModel = model),
                onIntent = {},
                availableModels = listOf(model),
                onModelSelected = { selected = it },
            )
        }

        compose.onNodeWithTag("model-selector").assertIsDisplayed().performClick()
        compose.onNodeWithText("切换模型").assertIsDisplayed()
        compose.onNodeWithText("GPT-4o").assertIsDisplayed()
        compose.onNodeWithText("图片").assertIsDisplayed()
        compose.onNodeWithText("添加模型").assertIsDisplayed()
        compose.onNodeWithText("GPT-4o").performClick()
        assertEquals(model, selected)
    }

    private fun message(id: String, role: Role, text: String, status: MessageStatus = MessageStatus.COMPLETED) =
        ChatMessage(id, "conversation", role, text, status = status)
}
