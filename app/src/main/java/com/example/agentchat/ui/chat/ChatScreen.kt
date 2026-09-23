package com.example.agentchat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.example.agentchat.data.attachment.AttachmentValidator
import com.example.agentchat.data.attachment.AttachmentValidationReason
import com.example.agentchat.data.voice.VoiceInputState
import com.example.agentchat.domain.model.ModelConfig

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onModelClick: () -> Unit = {},
    availableModels: List<ModelConfig> = emptyList(),
    onModelSelected: (ModelConfig) -> Unit = {},
    onModelEdit: (ModelConfig) -> Unit = {},
    onAddModelClick: () -> Unit = onModelClick,
    onHistoryClick: () -> Unit = {},
    onNewConversation: () -> Unit = {},
    onAttachmentClick: () -> Unit = {},
    onVoiceClick: () -> Unit = {},
    onVoicePressStart: (() -> Unit)? = null,
    onVoicePressEnd: () -> Unit = {},
    onVoiceCancel: () -> Unit = {},
    voiceInputState: VoiceInputState = VoiceInputState.IDLE,
    voiceError: String? = null,
) {
    val state by viewModel.uiState.collectAsState()
    ChatScreenContent(
        state = state,
        onIntent = { viewModel.onIntent(it) },
        onModelClick = onModelClick,
        availableModels = availableModels,
        onModelSelected = onModelSelected,
        onModelEdit = onModelEdit,
        onAddModelClick = onAddModelClick,
        onAttachmentClick = onAttachmentClick,
        onVoiceClick = onVoiceClick,
        onVoicePressStart = onVoicePressStart,
        onVoicePressEnd = onVoicePressEnd,
        onVoiceCancel = onVoiceCancel,
        voiceInputState = voiceInputState,
        voiceError = voiceError,
        onHistoryClick = onHistoryClick,
        onNewConversation = onNewConversation,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreenContent(
    state: ChatUiState,
    onIntent: (ChatIntent) -> Unit,
    onModelClick: () -> Unit = {},
    availableModels: List<ModelConfig> = emptyList(),
    onModelSelected: (ModelConfig) -> Unit = {},
    onModelEdit: (ModelConfig) -> Unit = {},
    onAddModelClick: () -> Unit = onModelClick,
    onAttachmentClick: () -> Unit = {},
    onVoiceClick: () -> Unit = {},
    onVoicePressStart: (() -> Unit)? = null,
    onVoicePressEnd: () -> Unit = {},
    onVoiceCancel: () -> Unit = {},
    voiceInputState: VoiceInputState = VoiceInputState.IDLE,
    voiceError: String? = null,
    onHistoryClick: () -> Unit = {},
    onNewConversation: () -> Unit = {},
) {
    var showModelSheet by remember { mutableStateOf(false) }
    val messageListState = rememberLazyListState()
    val attachmentReason = state.selectedModel?.let { AttachmentValidator.validate(state.attachments, it).reason }
    val attachmentError = attachmentReason?.let { it.displayMessage() }
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.id, state.messages.lastOrNull()?.text) {
        if (state.messages.isNotEmpty()) {
            messageListState.scrollToItem(state.messages.lastIndex)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (availableModels.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .testTag("model-selector")
                            .clickable { showModelSheet = true }
                            .semantics {
                                contentDescription = "切换模型，当前 ${state.selectedModel?.displayName ?: "未选择"}"
                                role = Role.DropdownList
                                stateDescription = if (showModelSheet) "已展开" else "已收起"
                            }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            state.selectedModel?.displayName ?: "选择模型",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "⌄",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                } else {
                    Text("Agent Chat", style = MaterialTheme.typography.titleLarge)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onNewConversation) { Text("新会话") }
                TextButton(onClick = onHistoryClick) { Text("历史") }
            }
        }
        if (state.isStreaming) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.messages.isEmpty()) {
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("开始一段新的对话", style = MaterialTheme.typography.titleMedium)
                    Text("选择模型后，在下方输入消息", style = MaterialTheme.typography.bodyMedium)
                    if (availableModels.isEmpty()) {
                        AddModelCta(onClick = onAddModelClick)
                    }
                }
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                if (availableModels.isEmpty()) {
                    AddModelCta(onClick = onAddModelClick)
                }
                LazyColumn(
                    state = messageListState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                ) {
                    items(state.messages, key = { it.id }) { message -> MessageBubble(message, onRetry = { onIntent(ChatIntent.RetryAssistant(message.id)) }) }
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        Composer(
            draft = state.draft,
            isStreaming = state.isStreaming,
            attachments = state.attachments,
            onDraftChanged = { onIntent(ChatIntent.DraftChanged(it)) },
            onSend = { onIntent(if (state.isStreaming) ChatIntent.Stop else ChatIntent.Send) },
            onAttachmentClick = onAttachmentClick,
            onVoiceClick = onVoiceClick,
            onVoicePressStart = onVoicePressStart,
            onVoicePressEnd = onVoicePressEnd,
            onVoiceCancel = onVoiceCancel,
            voiceInputState = voiceInputState,
            voiceError = voiceError,
            onRemoveAttachment = { onIntent(ChatIntent.RemoveAttachment(it)) },
            sendDisabledReason = attachmentError,
        )
    }

    if (showModelSheet) {
        ModalBottomSheet(
            onDismissRequest = { showModelSheet = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(),
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            ) {
                Text(
                    "切换模型",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                availableModels.forEach { model ->
                    val selected = model.id == state.selectedModel?.id
                    ListItem(
                        headlineContent = { Text(if (selected) "✓ ${model.displayName}" else model.displayName) },
                        supportingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AssistChip(onClick = {}, label = { Text(model.protocol.name) })
                                if (model.supportsVision) AssistChip(onClick = {}, label = { Text("图片") })
                                if (model.supportsFiles) AssistChip(onClick = {}, label = { Text("文件") })
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("model-option-${model.id}")
                            .semantics {
                                stateDescription = if (selected) "已选中" else "未选中"
                            }
                            .clickable {
                                showModelSheet = false
                                onModelSelected(model)
                            },
                        tonalElevation = if (selected) 2.dp else 0.dp,
                        trailingContent = {
                            TextButton(
                                onClick = {
                                    showModelSheet = false
                                    onModelEdit(model)
                                },
                            ) { Text("编辑") }
                        },
                    )
                    androidx.compose.material3.HorizontalDivider()
                }
                TextButton(
                    onClick = {
                        showModelSheet = false
                        onAddModelClick()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .testTag("add-model-menu-item"),
                ) { Text("＋ 添加新模型") }
            }
        }
    }
}

@Composable
private fun AddModelCta(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .testTag("add-model-empty-state"),
        shape = RoundedCornerShape(16.dp),
    ) { Text("＋ 添加第三方 LLM") }
}

private fun AttachmentValidationReason.displayMessage() = when (this) {
    AttachmentValidationReason.EMPTY_URI -> "附件 URI 为空"
    AttachmentValidationReason.UNKNOWN_MIME -> "附件类型不支持"
    AttachmentValidationReason.DUPLICATE_URI -> "不能重复添加同一附件"
    AttachmentValidationReason.SIZE_UNAVAILABLE -> "无法读取附件大小"
    AttachmentValidationReason.TOO_LARGE -> "附件不能超过 10 MiB"
    AttachmentValidationReason.TOO_MANY -> "最多添加 5 个附件"
    AttachmentValidationReason.MODEL_UNSUPPORTED -> "当前模型不支持此附件类型"
}
