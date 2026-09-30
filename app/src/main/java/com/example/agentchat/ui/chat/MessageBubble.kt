package com.example.agentchat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.example.agentchat.R
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.MessageStatus
import com.example.agentchat.domain.model.Role

@Composable
fun MessageBubble(message: ChatMessage, onRetry: () -> Unit = {}) {
    if (message.role == Role.ASSISTANT) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.Top,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.app_icon),
                contentDescription = "卡皮巴拉助手",
                modifier = Modifier.size(36.dp).clip(CircleShape),
            )
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                if (message.status == MessageStatus.STREAMING && message.text.isBlank()) {
                    ThinkingCard()
                } else {
                    MessageCard(message, onRetry)
                }
            }
        }
    } else {
        MessageCard(message, onRetry)
    }
}

@Composable
private fun ThinkingCard() {
    val transition = rememberInfiniteTransition(label = "thinking-shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "thinking-shimmer-progress",
    )
    val startX = -180f + progress * 360f
    val brush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        ),
        start = Offset(startX, 0f),
        end = Offset(startX + 180f, 0f),
    )
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = "小卡皮正在思考……",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge.copy(brush = brush),
        )
    }
}

@Composable
private fun MessageCard(message: ChatMessage, onRetry: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(if (message.role == Role.USER) "你" else "助手", style = MaterialTheme.typography.labelMedium)
            MarkdownText(message.text)
            if (message.attachments.isNotEmpty()) {
                Row {
                    message.attachments.forEach { attachment ->
                        AssistChip(onClick = {}, label = { Text(attachment.name) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
            Row {
                AssistChip(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("message", message.text))
                }, label = { Text("复制") })
                if (message.status == MessageStatus.FAILED) {
                    AssistChip(onClick = onRetry, label = { Text("重试") }, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun MarkdownText(text: String) {
    var inCode = false
    Column(Modifier.padding(top = 4.dp)) {
        text.lines().forEach { line ->
            when {
                line.trimStart().startsWith("```") -> {
                    inCode = !inCode
                    if (inCode) Text("代码块", style = MaterialTheme.typography.labelSmall)
                }
                inCode -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(line, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(8.dp)) }
                line.trimStart().startsWith("#") -> Text(
                    line.trimStart().trimStart('#').trim(),
                    style = MaterialTheme.typography.titleMedium,
                )
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> Row {
                    Text("• ")
                    Text(line.trimStart().drop(2))
                }
                line.isNotBlank() -> Text(line)
                else -> Text(" ")
            }
        }
    }
}
