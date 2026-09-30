package com.example.agentchat

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.example.agentchat.data.attachment.rememberAttachmentPicker
import com.example.agentchat.data.location.LocationPermission
import com.example.agentchat.data.permission.PendingPermissionAction
import com.example.agentchat.data.permission.PermissionRequestState
import com.example.agentchat.data.permission.PermissionRequirement
import com.example.agentchat.data.voice.VoiceInputLifecycleObserver
import com.example.agentchat.data.voice.VoiceInputState
import com.example.agentchat.ui.chat.ChatIntent
import com.example.agentchat.ui.chat.ChatScreen
import com.example.agentchat.ui.history.HistoryScreen
import com.example.agentchat.ui.modelconfig.ModelConfigDialog
import com.example.agentchat.ui.knowledge.KnowledgeScreen
import com.example.agentchat.ui.skill.SkillScreen
import com.example.agentchat.data.calendar.CalendarEventDraft
import com.example.agentchat.ui.theme.AgentChatTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AgentChatApplication).container
        setContent { AgentChatContent(container) }
        openConversationFromIntent(container, intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openConversationFromIntent((application as AgentChatApplication).container, intent)
    }

    private fun openConversationFromIntent(container: AppContainer, intent: Intent?) {
        val conversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID) ?: return
        lifecycleScope.launch {
            val messages = container.localHistoryRepository.observeMessages(conversationId).first()
            container.chatViewModel.loadConversation(conversationId, messages)
        }
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
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
            var pendingCalendarAction by remember { mutableStateOf<CalendarEventDraft?>(null) }
            var calendarReadPending by remember { mutableStateOf(false) }
            var notificationPermissionAsked by remember { mutableStateOf(false) }
            val permissionRequestState = remember { PermissionRequestState() }
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
                    LocationPermission.isGranted { permission -> context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED },
                )
            }
            var locationServiceEnabled by remember { mutableStateOf(container.locationProvider.isLocationEnabled()) }
            var sendWithPermissions: () -> Unit = {}
            val requestNotificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                val action = permissionRequestState.finish(granted)
                if (action == PendingPermissionAction.SendMessage || !granted) {
                    sendWithPermissions()
                }
            }
            val requestAudioPermission = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                val action = permissionRequestState.finish(granted)
                if (granted && action == PendingPermissionAction.StartVoice) voiceController.start()
                else if (!granted) voiceController.reportPermissionDenied()
            }
            val requestLocationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { granted ->
                val locationGranted = LocationPermission.isGranted { permission -> granted[permission] == true }
                locationPermissionGranted = locationGranted
                locationServiceEnabled = container.locationProvider.isLocationEnabled()
                val action = permissionRequestState.finish(locationGranted)
                if (locationGranted && action == PendingPermissionAction.SendMessage) {
                    sendWithPermissions()
                }
            }
            val createCalendarEvent: (CalendarEventDraft) -> Unit = { draft ->
                container.applicationScope.launch {
                    try {
                        container.calendarRepository.insertEvent(draft)
                        container.chatViewModel.onCalendarActionResult(true)
                    } catch (error: Throwable) {
                        container.chatViewModel.onCalendarActionResult(false, error.message ?: "日程创建失败")
                    }
                }
            }
            val requestCalendarPermissions = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { permissions ->
                val draft = pendingCalendarAction
                pendingCalendarAction = null
                val readPending = calendarReadPending
                calendarReadPending = false
                val readGranted = permissions[Manifest.permission.READ_CALENDAR] == true
                val writeGranted = permissions[Manifest.permission.WRITE_CALENDAR] == true
                if (draft != null && writeGranted) {
                    permissionRequestState.finish(granted = true)
                    createCalendarEvent(draft)
                } else if (draft != null) {
                    permissionRequestState.finish(granted = false)
                    container.chatViewModel.onCalendarActionResult(false, "未获得写入日历权限")
                } else if (readPending) {
                    val action = permissionRequestState.finish(readGranted)
                    if (readGranted && action == PendingPermissionAction.SendMessage) {
                        sendWithPermissions()
                    }
                } else {
                    permissionRequestState.finish(readGranted)
                }
            }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        locationPermissionGranted = LocationPermission.isGranted { permission -> context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }
                        locationServiceEnabled = container.locationProvider.isLocationEnabled()
                    }
                }
                lifecycleOwner?.lifecycle?.addObserver(observer)
                onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
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
            val knowledgeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri -> uri?.let(container.knowledgeViewModel::importUri) }
            val skillLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri -> uri?.let(container.skillViewModel::importUri) }
            val startVoice: () -> Unit = {
                voiceController.startOrRequestPermission(
                    hasPermission = (lifecycleOwner as? ComponentActivity)?.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                    requestPermission = {
                        permissionRequestState.begin(PendingPermissionAction.StartVoice)
                        requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }
            fun requestSendWithPermissions() {
                val query = chatState.draft.trim()
                when {
                    PermissionRequirement.needsCurrentLocation(query) && !locationPermissionGranted -> {
                        permissionRequestState.begin(PendingPermissionAction.SendMessage)
                        requestLocationPermission.launch(LocationPermission.permissions)
                    }
                    PermissionRequirement.needsCurrentLocation(query) && !locationServiceEnabled -> {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                    PermissionRequirement.needsCalendarRead(query) && !container.calendarRepository.hasReadPermission() -> {
                        permissionRequestState.begin(PendingPermissionAction.SendMessage)
                        calendarReadPending = true
                        requestCalendarPermissions.launch(arrayOf(Manifest.permission.READ_CALENDAR))
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                        !notificationPermissionAsked -> {
                        permissionRequestState.begin(PendingPermissionAction.SendMessage)
                        notificationPermissionAsked = true
                        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    else -> container.chatViewModel.onIntent(ChatIntent.Send)
                }
            }
            sendWithPermissions = { requestSendWithPermissions() }
            when (page) {
                Page.CHAT -> ChatScreen(
                    viewModel = container.chatViewModel,
                    onSend = { requestSendWithPermissions() },
                    onModelClick = { openModelConfig(null) },
                    availableModels = configState.configs.filter { it.enabled },
                    onModelSelected = { config ->
                        container.chatViewModel.setModel(config, container.providerRegistry.providerFor(config))
                    },
                    onModelEdit = { config -> openModelConfig(config) },
                    onAddModelClick = { openModelConfig(null) },
                    onHistoryClick = { page = Page.HISTORY },
                    onKnowledgeClick = { page = Page.KNOWLEDGE },
                    onSkillsClick = { page = Page.SKILLS },
                    availableSkills = container.skillViewModel.uiState.collectAsState().value.skills,
                    locationPermissionGranted = locationPermissionGranted,
                    locationServiceEnabled = locationServiceEnabled,
                    onLocationSettingsClick = {
                        if (!locationPermissionGranted) requestLocationPermission.launch(LocationPermission.permissions)
                        else context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    },
                    onCalendarConfirm = {
                        chatState.pendingCalendarDraft?.let { draft ->
                            pendingCalendarAction = draft
                            val missingPermissions = listOf(
                                Manifest.permission.READ_CALENDAR,
                                Manifest.permission.WRITE_CALENDAR,
                            ).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                            if (missingPermissions.isEmpty()) createCalendarEvent(draft)
                            else {
                                permissionRequestState.begin(PendingPermissionAction.CreateCalendarEvent)
                                requestCalendarPermissions.launch(missingPermissions.toTypedArray())
                            }
                        }
                    },
                    onCalendarCancel = { container.chatViewModel.dismissCalendarDraft() },
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
                Page.KNOWLEDGE -> KnowledgeScreen(
                    viewModel = container.knowledgeViewModel,
                    onImportClick = { knowledgeLauncher.launch(arrayOf("text/plain", "text/markdown", "application/json")) },
                    onBack = { page = Page.CHAT },
                )
                Page.SKILLS -> SkillScreen(
                    viewModel = container.skillViewModel,
                    onImportClick = { skillLauncher.launch(arrayOf("text/plain", "text/markdown")) },
                    onBack = { page = Page.CHAT },
                    onManualSelect = { skillId ->
                        container.chatViewModel.onIntent(ChatIntent.SkillSelected(skillId))
                        page = Page.CHAT
                    },
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

private enum class Page { CHAT, HISTORY, KNOWLEDGE, SKILLS }
