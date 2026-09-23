package com.example.agentchat.ui.chat

import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agentchat.data.voice.VoiceInputState
import com.example.agentchat.data.voice.SpeechRecognizerClient
import com.example.agentchat.data.voice.SpeechRecognizerListener
import com.example.agentchat.data.voice.VoiceInputController
import com.example.agentchat.data.secret.SecretStore
import com.example.agentchat.domain.model.ChatEvent
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.model.ProviderProtocol
import com.example.agentchat.domain.provider.ModelProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState

@RunWith(AndroidJUnit4::class)
class VoiceComposerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun permissionDeniedFallsBackToReadableTextInputError() {
        compose.setContent { Composer("", false, onDraftChanged = {}, onSend = {}, voiceError = "未获得麦克风权限，已切换为文字输入") }
        compose.onNodeWithText("未获得麦克风权限，已切换为文字输入").assertIsDisplayed()
        compose.onNodeWithContentDescription("语音输入，按住说话").assertExists()
    }

    @Test
    fun listeningStateShowsStopControl() {
        compose.setContent { Composer("", false, onDraftChanged = {}, onSend = {}, voiceInputState = VoiceInputState.LISTENING) }
        compose.onNodeWithText("转文字").assertExists()
        compose.onNodeWithText("取消").assertExists()
    }

    @Test
    fun transcriptCallbackActuallyRefillsDraftWithoutSending() {
        val viewModel = ChatViewModel(
            provider = EmptyProvider,
            secretStore = EmptySecrets,
            appendMessage = { it },
            updateAssistantMessage = { _, _, _ -> },
        )
        val recognizer = FakeRecognizer()
        val controller = VoiceInputController(
            recognizerFactory = { recognizer },
            onTranscript = viewModel::onVoiceTranscript,
        )
        compose.setContent {
            val state by controller.state.collectAsState()
            ChatScreen(
                viewModel = viewModel,
                voiceInputState = state,
                onVoiceClick = controller::start,
            )
        }
        compose.onNodeWithContentDescription("语音输入，按住说话").performClick()
        recognizer.emitResults("hello from voice")
        compose.onNodeWithText("hello from voice").assertExists()
        org.junit.Assert.assertEquals("hello from voice", viewModel.uiState.value.draft)
        org.junit.Assert.assertTrue(viewModel.uiState.value.messages.isEmpty())
    }

    @Test
    fun unavailableAndRecognitionErrorsAreReadable() {
        compose.setContent { Composer("", false, onDraftChanged = {}, onSend = {}, voiceError = "当前设备不支持语音输入") }
        compose.onNodeWithText("当前设备不支持语音输入").assertExists()
    }

    private class FakeRecognizer : SpeechRecognizerClient {
        private var listener: SpeechRecognizerListener? = null
        override fun setRecognitionListener(listener: SpeechRecognizerListener?) { this.listener = listener }
        override fun startListening(intent: Intent) { listener?.onReadyForSpeech(Bundle()) }
        override fun stopListening() = Unit
        override fun cancel() = Unit
        override fun destroy() = Unit
        fun emitResults(text: String) {
            listener?.onResults(Bundle().apply { putStringArrayList("results_recognition", arrayListOf(text)) })
        }
    }

    private object EmptyProvider : ModelProvider {
        override fun stream(config: ModelConfig, apiKey: String, messages: List<ChatMessage>): Flow<ChatEvent> = emptyFlow()
    }

    private object EmptySecrets : SecretStore {
        override suspend fun putApiKey(configId: String, apiKey: String) = Unit
        override suspend fun getApiKey(configId: String): String? = null
        override suspend fun deleteApiKey(configId: String) = Unit
        override suspend fun clearAllApiKeys() = Unit
        override suspend fun snapshotApiKeys() = com.example.agentchat.data.secret.ApiKeySnapshot(emptyMap())
        override suspend fun restoreApiKeys(snapshot: com.example.agentchat.data.secret.ApiKeySnapshot) = Unit
    }
}
