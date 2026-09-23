package com.example.agentchat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.example.agentchat.domain.model.Attachment
import com.example.agentchat.data.voice.VoiceInputState

@Composable
fun Composer(
    draft: String,
    isStreaming: Boolean,
    attachments: List<Attachment> = emptyList(),
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onAttachmentClick: () -> Unit = {},
    onVoiceClick: () -> Unit = {},
    onVoicePressStart: (() -> Unit)? = null,
    onVoicePressEnd: () -> Unit = {},
    onVoiceCancel: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    sendDisabledReason: String? = null,
    voiceInputState: VoiceInputState = VoiceInputState.IDLE,
    voiceError: String? = null,
) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(8.dp)) {
        if (attachments.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(attachments, key = { it.id }) { attachment ->
                    androidx.compose.material3.AssistChip(
                        onClick = {},
                        label = {
                            androidx.compose.foundation.layout.Column {
                                Text(attachment.name)
                                Text("${attachment.mimeType} · ${formatBytes(attachment.sizeBytes)}")
                            }
                        },
                        trailingIcon = { TextButton(onClick = { onRemoveAttachment(attachment.id) }) { Text("移除") } },
                    )
                }
            }
        }
        if (voiceInputState == VoiceInputState.LISTENING || voiceInputState == VoiceInputState.TRANSCRIBING) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                tonalElevation = 4.dp,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(if (voiceInputState == VoiceInputState.LISTENING) "正在录音，松开转文字" else "正在转换语音…")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onVoiceCancel) { Text("取消") }
                        TextButton(onClick = onVoicePressEnd, enabled = voiceInputState == VoiceInputState.LISTENING) { Text("转文字") }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onAttachmentClick, modifier = Modifier.semantics { contentDescription = "添加附件（图片或文件）" }) { Text("📎") }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChanged,
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat-input")
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                            onSend()
                            true
                        } else {
                            false
                        }
                    },
                placeholder = { Text("输入消息…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            if (isStreaming) {
                IconButton(onClick = onSend, modifier = Modifier.semantics { contentDescription = "停止生成" }) { Text("■") }
            } else {
                IconButton(
                    onClick = { if (onVoicePressStart == null) onVoiceClick() },
                    modifier = Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                (onVoicePressStart ?: onVoiceClick)()
                                if (tryAwaitRelease()) onVoicePressEnd() else onVoiceCancel()
                            })
                        }
                        .semantics { contentDescription = "语音输入，按住说话" },
                ) { Text("🎙") }
            }
        }
        sendDisabledReason?.let { Text(it) }
        voiceError?.let { Text(it) }
    }
}

private fun formatBytes(size: Long): String = when {
    size >= 1024 * 1024 -> "${size / (1024 * 1024)} MiB"
    size >= 1024 -> "${size / 1024} KiB"
    else -> "$size B"
}
