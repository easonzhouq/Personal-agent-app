package com.example.agentchat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import com.example.agentchat.data.attachment.rememberAttachmentPicker
import com.example.agentchat.data.voice.VoiceInputLifecycleObserver
import com.example.agentchat.data.voice.VoiceInputState
import com.example.agentchat.ui.chat.ChatIntent
import com.example.agentchat.ui.chat.ChatScreen
import com.example.agentchat.ui.history.HistoryScreen
import com.example.agentchat.ui.modelconfig.ModelConfigDialog
import com.example.agentchat.ui.theme.AgentChatTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AgentChatApplication).container
        setContent { AgentChatContent(container) }
    }
}

@Composable
internal fun AgentChatContent(container: AppContainer) {
    AgentChatTheme {
        Surface {
            val configState by container.modelConfigViewModel.uiState.collectAsState()
            val chatState by container.chatViewModel.uiState.collectAsState()
            var page by remember { mutableStateOf(Page.CHAT) }
            var showModelConfig by remember { mutableStateOf(false) }
            val openModelConfig: (com.example.agentchat.domain.model.ModelConfig?) -> Unit = { config ->
                if (config == null) container.modelConfigViewModel.resetForm()
                else container.modelConfigViewModel.edit(config)
                showModelConfig = true
            }
            val voiceController = container.voiceInputController
            val voiceState by voiceController.state.collectAsState()
            val voiceError by voiceController.errorMessage.collectAsState()
            val context = LocalContext.current
            val lifecycleOwner = context as? LifecycleOwner
            var locationPermissionGranted by remember {
                mutableStateOf(
                    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                )
            }
            val requestAudioPermission = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) voiceController.start() else voiceController.reportPermissionDenied()
            }
            val requestLocationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted -> locationPermissionGranted = granted }

            LaunchedEffect(Unit) {
                if (!locationPermissionGranted) {
                    requestLocationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            }

            LaunchedEffect(configState.configs, chatState.selectedConfigId, chatState.selectedModel) {
                val selected = chatState.selectedConfigId?.let { id -> configState.configs.firstOrNull { it.id == id } }
                when {
                    selected == null && chatState.selectedConfigId == null -> {
                        configState.configs.firstOrNull { it.isDefault && it.enabled }?.let { config ->
                            container.chatViewModel.setModel(config, container.providerRegistry.providerFor(config))
                        }
                    }
                    chatState.selectedConfigId != null && (selected == null || !selected.enabled) -> {
                        container.chatViewModel.clearModel("当前模型已禁用或已删除，请选择其他模型")
                    }
                    selected != null && selected != chatState.selectedModel -> {
                        container.chatViewModel.setModel(selected, container.providerRegistry.providerFor(selected))
                    }
                }
            }

            LaunchedEffect(configState.lastSavedConfigId, configState.configs) {
                val savedConfig = configState.lastSavedConfigId?.let { id ->
                    configState.configs.firstOrNull { it.id == id }
                }
                if (savedConfig != null) {
                    container.chatViewModel.setModel(savedConfig, container.providerRegistry.providerFor(savedConfig))
                    container.modelConfigViewModel.consumeLastSavedConfigId()
                    showModelConfig = false
                }
            }

            DisposableEffect(voiceController, lifecycleOwner) {
                val observer = VoiceInputLifecycleObserver(voiceController)
                lifecycleOwner?.lifecycle?.addObserver(observer)
                onDispose {
                    lifecycleOwner?.lifecycle?.removeObserver(observer)
                    voiceController.dispose()
                }
            }

            val pickAttachments = rememberAttachmentPicker { result ->
                container.chatViewModel.onIntent(ChatIntent.AttachmentsSelected(result.accepted, result.rejected))
            }
            val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("text/markdown"),
            ) { uri -> container.exportController.onCreateDocumentResult(uri) }
            val startVoice: () -> Unit = {
                voiceController.startOrRequestPermission(
                    hasPermission = (lifecycleOwner as? ComponentActivity)?.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                    requestPermission = { requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO) },
                )
            }
            when (page) {
                Page.CHAT -> ChatScreen(
                    viewModel = container.chatViewModel,
                    onModelClick = { openModelConfig(null) },
                    availableModels = configState.configs.filter { it.enabled },
                    onModelSelected = { config ->
                        container.chatViewModel.setModel(config, container.providerRegistry.providerFor(config))
                    },
                    onModelEdit = { config -> openModelConfig(config) },
                    onAddModelClick = { openModelConfig(null) },
                    onHistoryClick = { page = Page.HISTORY },
                    onNewConversation = { container.applicationScope.launch { container.chatViewModel.startNewConversation() } },
                    onAttachmentClick = pickAttachments,
                    voiceInputState = voiceState,
                    voiceError = voiceError,
                    onVoiceClick = startVoice,
                    onVoicePressStart = startVoice,
                    onVoicePressEnd = { voiceController.stop() },
                    onVoiceCancel = { voiceController.cancel() },
                )
                Page.HISTORY -> HistoryScreen(
                    viewModel = container.historyViewModel,
                    currentDraftUris = { container.chatViewModel.draftAttachmentUris() },
                    onOpen = { id ->
                        container.historyViewModel.openConversation(id) { conversationId, messages ->
                            container.chatViewModel.loadConversation(conversationId, messages)
                            page = Page.CHAT
                        }
                    },
                    onExport = { id ->
                        container.exportController.requestDocument(id)
                        exportLauncher.launch("agent-chat.md")
                    },
                    onBack = { page = Page.CHAT },
                )
            }
            if (showModelConfig) {
                ModelConfigDialog(
                    viewModel = container.modelConfigViewModel,
                    onDismiss = { if (!configState.isSaving) showModelConfig = false },
                )
            }
        }
    }
}

private enum class Page { CHAT, HISTORY }
