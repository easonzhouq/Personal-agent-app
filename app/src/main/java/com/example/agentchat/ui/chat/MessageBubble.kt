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
import androidx.compose.runtime.Composable
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
                MessageCard(message, onRetry)
            }
        }
    } else {
        MessageCard(message, onRetry)
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
