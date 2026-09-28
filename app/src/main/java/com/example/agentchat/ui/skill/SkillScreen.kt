package com.example.agentchat.ui.skill

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.agentchat.domain.skill.Skill

@Composable
fun SkillScreen(
    viewModel: SkillViewModel,
    onImportClick: () -> Unit,
    onBack: () -> Unit,
    onManualSelect: (String?) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    SkillScreenContent(
        state = state,
        onImportClick = onImportClick,
        onToggle = viewModel::setEnabled,
        onDelete = viewModel::delete,
        onBack = onBack,
        onManualSelect = onManualSelect,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillScreenContent(
    state: SkillUiState,
    onImportClick: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    onManualSelect: (String?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        CenterAlignedTopAppBar(
            title = { Text("Skills") },
            navigationIcon = {
                TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回聊天" }) {
                    Text("返回")
                }
            },
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本地 Skill", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "导入 SKILL.md，让 Agent 按任务自动匹配工作方式。Skill 不能执行未经授权的代码。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onImportClick,
                        enabled = !state.isImporting,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(if (state.isImporting) "导入中…" else "导入 Skill") }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
            if (state.skills.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("还没有 Skill", style = MaterialTheme.typography.titleMedium)
                        Text("导入后可在对话中自动匹配", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.skills, key = { it.id }) { skill ->
                        SkillCard(skill, onToggle, onDelete, onManualSelect)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: Skill,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onManualSelect: (String?) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(skill.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "v${skill.version} · ${skill.description}",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = skill.enabled,
                    onCheckedChange = { onToggle(skill.id, it) },
                    modifier = Modifier.semantics {
                        contentDescription = if (skill.enabled) "停用 Skill ${skill.name}" else "启用 Skill ${skill.name}"
                    },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onManualSelect(skill.id) }) { Text("用于下一条") }
                TextButton(onClick = { onDelete(skill.id) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
