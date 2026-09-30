package com.example.agentchat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.example.agentchat.data.attachment.AttachmentValidator
import com.example.agentchat.data.attachment.AttachmentValidationReason
import com.example.agentchat.data.voice.VoiceInputState
import com.example.agentchat.data.calendar.CalendarEventDraft
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.skill.Skill
import java.time.format.DateTimeFormatter

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onModelClick: () -> Unit = {},
    availableModels: List<ModelConfig> = emptyList(),
    onModelSelected: (ModelConfig) -> Unit = {},
    onModelEdit: (ModelConfig) -> Unit = {},
    onAddModelClick: () -> Unit = onModelClick,
    onHistoryClick: () -> Unit = {},
    onKnowledgeClick: () -> Unit = {},
    onSkillsClick: () -> Unit = {},
    availableSkills: List<Skill> = emptyList(),
    locationPermissionGranted: Boolean = true,
    locationServiceEnabled: Boolean = true,
    onLocationSettingsClick: () -> Unit = {},
    onNewConversation: () -> Unit = {},
    onAttachmentClick: () -> Unit = {},
    onVoiceClick: () -> Unit = {},
    onVoicePressStart: (() -> Unit)? = null,
    onVoicePressEnd: () -> Unit = {},
    onVoiceCancel: () -> Unit = {},
    voiceInputState: VoiceInputState = VoiceInputState.IDLE,
    voiceError: String? = null,
    onCalendarConfirm: () -> Unit = {},
    onCalendarCancel: () -> Unit = {},
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
        onCalendarConfirm = onCalendarConfirm,
        onCalendarCancel = onCalendarCancel,
        onHistoryClick = onHistoryClick,
        onKnowledgeClick = onKnowledgeClick,
        onSkillsClick = onSkillsClick,
        availableSkills = availableSkills,
        locationPermissionGranted = locationPermissionGranted,
        locationServiceEnabled = locationServiceEnabled,
        onLocationSettingsClick = onLocationSettingsClick,
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
    onCalendarConfirm: () -> Unit = {},
    onCalendarCancel: () -> Unit = {},
    onHistoryClick: () -> Unit = {},
    onKnowledgeClick: () -> Unit = {},
    onSkillsClick: () -> Unit = {},
    availableSkills: List<Skill> = emptyList(),
    locationPermissionGranted: Boolean = true,
    locationServiceEnabled: Boolean = true,
    onLocationSettingsClick: () -> Unit = {},
    onNewConversation: () -> Unit = {},
) {
    var showModelSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    val messageListState = rememberLazyListState()
    val attachmentReason = state.selectedModel?.let { AttachmentValidator.validate(state.attachments, it).reason }
    val attachmentError = attachmentReason?.let { it.displayMessage() }
    val viewportEndOffset = messageListState.layoutInfo.viewportEndOffset
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.id, state.messages.lastOrNull()?.text, viewportEndOffset) {
        if (state.messages.isNotEmpty()) {
            messageListState.scrollToItem(state.messages.lastIndex)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .testTag("chat-root")
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
                    Surface(
                        modifier = Modifier
                            .widthIn(min = 120.dp, max = 190.dp)
                            .height(42.dp)
                            .testTag("model-selector")
                            .clickable { showModelSheet = true }
                            .semantics {
                                contentDescription = "切换模型，当前 ${state.selectedModel?.displayName ?: "未选择"}"
                                role = Role.DropdownList
                                stateDescription = if (showModelSheet) "已展开" else "已收起"
                            }
                            .border(
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
                                RoundedCornerShape(14.dp),
                            ),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                        tonalElevation = 0.dp,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    state.selectedModel?.displayName ?: "选择模型",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                            ChevronDown70(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp).testTag("model-selector-arrow"),
                            )
                        }
                    }
                } else {
                    Text("Agent Chat", style = MaterialTheme.typography.titleLarge)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onNewConversation,
                    modifier = Modifier.semantics { contentDescription = "新对话" },
                ) { NewConversationBubbleIcon(MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(22.dp)) }
                Box {
                    IconButton(
                        onClick = { showMoreMenu = true },
                        modifier = Modifier.semantics { contentDescription = "更多功能" },
                    ) { Text("⋯", color = MaterialTheme.colorScheme.primary, fontSize = 24.sp) }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("历史") },
                            onClick = { showMoreMenu = false; onHistoryClick() },
                        )
                        DropdownMenuItem(
                            text = { Text("知识库") },
                            onClick = { showMoreMenu = false; onKnowledgeClick() },
                        )
                        DropdownMenuItem(
                            text = {
                                val selectedSkill = state.selectedSkillId?.let { id -> availableSkills.firstOrNull { it.id == id } }
                                Text(selectedSkill?.let { "Skill：${it.name}" } ?: "Skills")
                            },
                            onClick = { showMoreMenu = false; onSkillsClick() },
                        )
                    }
                }
            }
        }
        if (!locationPermissionGranted || !locationServiceEnabled) {
            LocationPermissionBanner(
                permissionGranted = locationPermissionGranted,
                serviceEnabled = locationServiceEnabled,
                onSettingsClick = onLocationSettingsClick,
            )
        }
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
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                ) {
                    items(state.messages, key = { it.id }) { message -> MessageBubble(message, onRetry = { onIntent(ChatIntent.RetryAssistant(message.id)) }) }
                }
            }
        }
        state.pendingCalendarDraft?.let { draft ->
            CalendarConfirmationCard(draft, onConfirm = onCalendarConfirm, onCancel = onCalendarCancel)
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
private fun LocationPermissionBanner(
    permissionGranted: Boolean,
    serviceEnabled: Boolean,
    onSettingsClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    !permissionGranted -> "未获得定位权限，无法查询当前位置天气"
                    !serviceEnabled -> "系统定位服务已关闭，无法查询当前位置天气"
                    else -> ""
                },
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onSettingsClick) { Text("去设置") }
        }
    }
}

@Composable
private fun NewConversationBubbleIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.7.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.12f, size.height * 0.10f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.76f, size.height * 0.70f),
            cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx()),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.31f, size.height * 0.78f),
            end = Offset(size.width * 0.22f, size.height * 0.92f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.22f, size.height * 0.92f),
            end = Offset(size.width * 0.42f, size.height * 0.82f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.34f, size.height * 0.45f),
            end = Offset(size.width * 0.66f, size.height * 0.45f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.50f, size.height * 0.29f),
            end = Offset(size.width * 0.50f, size.height * 0.61f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ChevronDown70(color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val left = Offset(size.width * 0.22f, size.height * 0.25f)
        val vertex = Offset(size.width * 0.50f, size.height * 0.75f)
        val right = Offset(size.width * 0.78f, size.height * 0.25f)
        drawLine(color, left, vertex, strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, vertex, right, strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun CalendarConfirmationCard(
    draft: CalendarEventDraft,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val formatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("确认创建日程", style = MaterialTheme.typography.titleMedium)
            Text(draft.title, modifier = Modifier.padding(top = 4.dp))
            Text("${draft.startAt.format(formatter)} - ${draft.endAt.format(formatter)}", style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) { Text("取消") }
                TextButton(onClick = onConfirm) { Text("确认创建") }
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
