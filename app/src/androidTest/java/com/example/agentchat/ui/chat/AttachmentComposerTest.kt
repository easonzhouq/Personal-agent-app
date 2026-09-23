package com.example.agentchat.ui.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agentchat.domain.model.Attachment
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.model.ProviderProtocol
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentComposerTest {
    @get:Rule val compose = createComposeRule()
    private val config = ModelConfig("cfg", "model", "", "", ProviderProtocol.CUSTOM, supportsFiles = true)

    @Test fun chipShowsMetadataAndRemoveAction() {
        val attachment = Attachment("a", "notes.txt", "text/plain", 4, "content://notes")
        var removed = false
        compose.setContent { ChatScreenContent(ChatUiState(attachments = listOf(attachment), selectedModel = config), { if (it is ChatIntent.RemoveAttachment) removed = true }) }
        compose.onNodeWithText("notes.txt").assertIsDisplayed()
        compose.onNodeWithText("text/plain · 4 B").assertIsDisplayed()
        compose.onNodeWithText("移除").performClick()
        assertTrue(removed)
    }

    @Test fun unsupportedAttachmentDisablesSendAndExplainsWhy() {
        val attachment = Attachment("a", "photo.png", "image/png", 4, "content://photo")
        compose.setContent { ChatScreenContent(ChatUiState(draft = "send", attachments = listOf(attachment), selectedModel = config), {}) }
        compose.onAllNodesWithContentDescription("发送消息").assertCountEquals(0)
        compose.onNodeWithText("当前模型不支持此附件类型").assertIsDisplayed()
    }

    @Test fun supportedAttachmentAllowsSend() {
        val attachment = Attachment("a", "notes.txt", "text/plain", 4, "content://notes")
        compose.setContent { ChatScreenContent(ChatUiState(attachments = listOf(attachment), selectedModel = config), {}) }
        compose.onAllNodesWithContentDescription("发送消息").assertCountEquals(0)
    }

    @Test fun fiveLongAttachmentChipsRemainInScrollableList() {
        val attachments = (0 until 5).map { index ->
            Attachment("$index", "very-long-file-name-$index-${"x".repeat(40)}.txt", "text/plain", 4, "content://$index")
        }
        compose.setContent { Composer("", false, attachments, {}, {}) }
        attachments.forEach { compose.onNodeWithText(it.name).assertIsDisplayed() }
    }

    @Test fun emptyUriDisablesSend() {
        val attachment = Attachment("a", "empty", "text/plain", 4, "")
        compose.setContent { ChatScreenContent(ChatUiState(attachments = listOf(attachment), selectedModel = config), {}) }
        compose.onAllNodesWithContentDescription("发送消息").assertCountEquals(0)
        compose.onNodeWithText("附件 URI 为空").assertIsDisplayed()
    }

    @Test fun oversizedAttachmentDisablesSend() {
        val attachment = Attachment("a", "large.txt", "text/plain", 10 * 1024 * 1024 + 1L, "content://large")
        compose.setContent { ChatScreenContent(ChatUiState(attachments = listOf(attachment), selectedModel = config), {}) }
        compose.onAllNodesWithContentDescription("发送消息").assertCountEquals(0)
        compose.onNodeWithText("附件不能超过 10 MiB").assertIsDisplayed()
    }

    @Test fun duplicateAttachmentDisablesSend() {
        val first = Attachment("a", "one.txt", "text/plain", 4, "content://same")
        val second = first.copy(id = "b", name = "two.txt")
        compose.setContent { ChatScreenContent(ChatUiState(attachments = listOf(first, second), selectedModel = config), {}) }
        compose.onAllNodesWithContentDescription("发送消息").assertCountEquals(0)
        compose.onNodeWithText("不能重复添加同一附件").assertIsDisplayed()
    }
}
