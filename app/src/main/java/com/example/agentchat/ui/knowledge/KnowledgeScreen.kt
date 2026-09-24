package com.example.agentchat.ui.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.agentchat.data.rag.KnowledgeSource

@Composable
fun KnowledgeScreen(
    viewModel: KnowledgeViewModel,
    onImportClick: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    KnowledgeScreenContent(
        state = state,
        onImportClick = onImportClick,
        onDelete = viewModel::deleteSource,
        onBack = onBack,
    )
}

@Composable
fun KnowledgeScreenContent(
    state: KnowledgeUiState,
    onImportClick: () -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("知识库", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回聊天" }) { Text("返回") }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("导入 TXT、Markdown 或 JSON 文档", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onImportClick, enabled = !state.isImporting) {
                Text(if (state.isImporting) "导入中…" else "导入知识文件")
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
        if (state.sources.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("还没有知识文档", style = MaterialTheme.typography.titleMedium)
                Text("导入后，提问时会自动检索相关内容")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(state.sources, key = { it.sourceId }) { source ->
                    KnowledgeSourceRow(source, onDelete)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun KnowledgeSourceRow(source: KnowledgeSource, onDelete: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(source.displayName, style = MaterialTheme.typography.titleMedium)
            Text("${source.chunkCount} 个片段", style = MaterialTheme.typography.bodySmall)
        }
        IconButton(
            onClick = { onDelete(source.sourceId) },
            modifier = Modifier.semantics { contentDescription = "删除知识文档 ${source.displayName}" },
        ) { Text("删除") }
    }
}
