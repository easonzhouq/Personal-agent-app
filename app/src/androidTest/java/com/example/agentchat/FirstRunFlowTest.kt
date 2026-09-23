package com.example.agentchat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.example.agentchat.data.provider.ProviderRegistry
import com.example.agentchat.data.attachment.AttachmentPicker
import com.example.agentchat.data.attachment.ContentResolverAttachmentEncoder
import com.example.agentchat.data.attachment.pickAttachmentUris
import com.example.agentchat.data.provider.OpenAiCompatibleProvider
import com.example.agentchat.domain.model.ChatEvent
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.model.ProviderProtocol
import com.example.agentchat.domain.provider.ModelProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstRunFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var application: AgentChatApplication
    private val providerConfigs = mutableListOf<String>()

    @Before
    fun setUp() {
        application = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        application.container.database.clearAllTables()
        application.container.chatViewModel.loadConversation("default-conversation", emptyList())
        application.container.chatViewModel.clearModel()
        providerConfigs.clear()
        application.container.providerRegistry.setProviderFactory { config -> FakeStreamingProvider(config) { providerConfigs += it } }
    }

    @Test
    fun firstRunAddsModelStreamsMessageAndReopensSearchResult() {
        openAndUseModel()

        onComposer().performTextInput("hello from first run")
        compose.onNodeWithContentDescription("发送消息").performClick()
        compose.onNodeWithText("fake reply").assertIsDisplayed()

        compose.onNodeWithText("历史").performClick()
        compose.onNodeWithText("搜索标题或消息").performTextInput("hello from first run")
        compose.onNodeWithText("打开会话 hello from first run").performClick()
        compose.onNodeWithText("fake reply").assertIsDisplayed()
    }

    @Test
    fun productionWiringSwitchesModelAndDisabledCurrentModelCannotSend() {
        val first = model("First model", "first-model", isDefault = true)
        val second = model("Second model", "second-model")
        runBlocking {
            application.container.modelConfigRepository.save(first, null, true)
            application.container.modelConfigRepository.save(second, null, false)
        }
        compose.waitForIdle()

        compose.onNodeWithText("First model").performClick()
        compose.onNodeWithContentDescription("使用模型 Second model").performClick()
        compose.onNodeWithText("Second model").assertIsDisplayed()
        compose.onNodeWithText("输入消息…").performTextInput("switch model")
        compose.onNodeWithContentDescription("发送消息").performClick()
        compose.onNodeWithText("fake reply").assertIsDisplayed()
        org.junit.Assert.assertEquals("second-model", providerConfigs.last())

        compose.onNodeWithText("Second model").performClick()
        compose.onNodeWithContentDescription("启用模型 Second model").performClick()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("输入消息…").performTextInput("must not send")
        compose.onNodeWithContentDescription("发送消息").performClick()
        compose.onNodeWithText("请先选择模型").assertIsDisplayed()
    }

    @Test
    fun fakeContentResolverAttachmentFailureRestoresDraftAndAttachment() {
        val config = model("Files model", "files-model", isDefault = true).copy(supportsFiles = true)
        runBlocking { application.container.modelConfigRepository.save(config, null, true) }
        compose.waitForIdle()
        compose.onNodeWithText("Files model").performClick()
        compose.onNodeWithContentDescription("使用模型 Files model").performClick()

        val resolver = ContentResolver.wrap(FailingContentProvider())
        val picked = pickAttachmentUris(
            uris = listOf(Uri.parse("content://failing/notes")),
            takePermission = { true },
            readMetadata = { uri -> AttachmentPicker.readMetadataResult(resolver, uri) },
        )
        check(picked.accepted.size == 1)
        application.container.providerRegistry.setProviderFactory {
            OpenAiCompatibleProvider(attachmentEncoder = ContentResolverAttachmentEncoder(resolver = resolver))
        }
        compose.runOnUiThread {
            application.container.chatViewModel.onIntent(
                com.example.agentchat.ui.chat.ChatIntent.AttachmentsSelected(picked.accepted, picked.rejected),
            )
            application.container.chatViewModel.onIntent(com.example.agentchat.ui.chat.ChatIntent.DraftChanged("keep draft"))
            application.container.chatViewModel.onIntent(com.example.agentchat.ui.chat.ChatIntent.Send)
        }

        compose.onNodeWithText("keep draft").assertIsDisplayed()
        compose.onNodeWithText("notes.txt").assertIsDisplayed()
        compose.onNodeWithText("Unable to read attachment").assertIsDisplayed()
    }

    @Test
    fun voicePermissionDenialAndAttachmentFailureKeepTextDraft() {
        openAndUseModel()
        onComposer().performTextInput("keep this draft")

        compose.runOnUiThread {
            application.container.voiceInputController.reportPermissionDenied()
            application.container.chatViewModel.onIntent(
                com.example.agentchat.ui.chat.ChatIntent.AttachmentsSelected(
                    attachments = emptyList(),
                    rejected = listOf(
                        com.example.agentchat.data.attachment.AttachmentRejection(
                            "content://broken",
                            com.example.agentchat.data.attachment.AttachmentRejectionReason.UNKNOWN_MIME,
                        ),
                    ),
                ),
            )
        }

        compose.onNodeWithText("未获得麦克风权限，已切换为文字输入").assertIsDisplayed()
        compose.onNodeWithText("keep this draft").assertIsDisplayed()
        compose.onNodeWithText("部分附件未添加").assertIsDisplayed()
    }

    private fun openAndUseModel() {
        compose.onNodeWithText("选择模型").performClick()
        compose.onNodeWithText("显示名称").performTextInput("Fake model")
        compose.onNodeWithText("Base URL").performTextInput("https://fake.example")
        compose.onNodeWithText("模型名称").performTextInput("fake-model")
        compose.onNodeWithText("保存并设为默认").performClick()
        compose.onNodeWithText("使用此模型").performClick()
    }

    private fun onComposer() = compose.onNodeWithText("输入消息…")

    private fun model(name: String, modelName: String, isDefault: Boolean = false) = ModelConfig(
        id = name.lowercase().replace(' ', '-'),
        displayName = name,
        baseUrl = "https://fake.example",
        modelName = modelName,
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        supportsFiles = false,
        isDefault = isDefault,
    )
}

private class FakeStreamingProvider(private val config: ModelConfig, private val record: (String) -> Unit) : ModelProvider {
    override fun stream(
        config: ModelConfig,
        apiKey: String,
        messages: List<ChatMessage>,
    ): Flow<ChatEvent> = flowOf(ChatEvent.Started, ChatEvent.Delta("fake reply"), ChatEvent.Completed())

    init {
        check(config.modelName.isNotBlank())
        record(config.modelName)
    }
}

private class FailingContentProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "text/plain"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(arrayOf<Any?>("notes.txt", 4L)) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = throw FileNotFoundException("attachment unavailable")
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
