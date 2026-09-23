package com.example.agentchat.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun HistoryScreen(viewModel: HistoryViewModel, currentDraftUris: () -> Set<String>, onOpen: (String) -> Unit, onExport: (String) -> Unit = {}, onBack: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsState()
    Column(Modifier.padding(16.dp)) {
        Row { Text("历史会话"); Button(onClick = viewModel::requestClearAll) { Text("清空本地数据") }; Button(onClick = onBack) { Text("返回") } }
        OutlinedTextField(state.query, viewModel::updateQuery, modifier = Modifier.fillMaxWidth(), label = { Text("搜索标题或消息") })
        if (state.isSearching) Text("搜索中…")
        state.error?.let { Text(it) }
        LazyColumn { items(state.conversations, key = { it.id }) { conversation ->
            Row(Modifier.fillMaxWidth()) {
                val busy = conversation.id in state.busyIds
                Button(
                    onClick = { onOpen(conversation.id) },
                    enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "打开会话 ${conversation.title}" },
                ) { Text(conversation.title) }
                Button(onClick = { onExport(conversation.id) }, enabled = !busy) { Text("导出") }
                Button(onClick = { viewModel.requestDelete(conversation.id) }, enabled = !busy) { Text("删除") }
            }
        } }
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
