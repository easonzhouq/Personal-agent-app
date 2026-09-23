package com.example.agentchat.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun HistoryScreen(viewModel: HistoryViewModel, currentDraftUris: () -> Set<String>, onOpen: (String) -> Unit, onExport: (String) -> Unit = {}, onBack: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("历史会话", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = viewModel::requestClearAll) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onBack) { Text("返回") }
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::updateQuery,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            singleLine = true,
            placeholder = { Text("搜索标题或消息") },
        )
        if (state.isSearching) {
            Text("搜索中…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        if (state.conversations.isEmpty() && !state.isSearching) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("暂无历史会话", style = MaterialTheme.typography.titleMedium)
                Text("完成一次对话后，会显示在这里", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.conversations, key = { it.id }) { conversation ->
                    val busy = conversation.id in state.busyIds
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) { onOpen(conversation.id) }
                            .semantics { contentDescription = "打开会话 ${conversation.title}" },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    conversation.title,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.headlineSmall)
                            }
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { onExport(conversation.id) }, enabled = !busy) { Text("导出") }
                                TextButton(onClick = { viewModel.requestDelete(conversation.id) }, enabled = !busy) {
                                    Text("删除", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    state.exportResult?.let { Text(exportMessage(it)) }
    state.confirmDeleteId?.let { id -> AlertDialog(onDismissRequest = viewModel::cancelDelete, title = { Text("删除会话？") }, text = { Text("附件权限会按引用安全释放") }, confirmButton = { Button(onClick = { viewModel.confirmDelete(currentDraftUris()) }, enabled = id !in state.busyIds) { Text("删除") } }, dismissButton = { Button(onClick = viewModel::cancelDelete) { Text("取消") } }) }
    if (state.confirmClearAll) AlertDialog(onDismissRequest = viewModel::cancelClearAll, title = { Text("清空全部本地数据？") }, text = { Text("会删除本机历史、模型配置、API Key 和附件权限，且不可恢复") }, confirmButton = { Button(onClick = { viewModel.confirmClearAll(currentDraftUris()) }, enabled = "__clear_all__" !in state.busyIds) { Text("确认清空") } }, dismissButton = { Button(onClick = viewModel::cancelClearAll) { Text("取消") } })
}

private fun exportMessage(result: ExportResult) = when (result) {
    ExportResult.Success -> "导出成功"
    ExportResult.Cancelled -> "已取消导出"
    ExportResult.ConversationNotFound -> "会话不存在，无法导出"
    ExportResult.WriteFailed -> "导出写入失败"
    ExportResult.SecretReadFailed -> "API Key 解密失败，无法导出"
}
