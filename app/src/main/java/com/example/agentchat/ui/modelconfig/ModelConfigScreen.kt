package com.example.agentchat.ui.modelconfig

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.agentchat.domain.model.ProviderProtocol
import com.example.agentchat.data.provider.ProviderRegistry
import com.example.agentchat.data.provider.ConnectionResult

@Composable
fun ModelConfigScreen(viewModel: ModelConfigViewModel, providerRegistry: ProviderRegistry, onUse: (com.example.agentchat.domain.model.ModelConfig) -> Unit = {}, onBack: () -> Unit = {}, inDialog: Boolean = false) {
    val state by viewModel.uiState.collectAsState()
    var apiKeyInput by remember(state.editingId) { mutableStateOf("") }
    Column((if (inDialog) Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()) else Modifier).padding(16.dp)) {
        Row(Modifier.fillMaxWidth()) { Text("模型配置"); Button(onClick = viewModel::resetForm, enabled = !state.isSaving) { Text("新建配置") }; Button(onClick = onBack, enabled = !state.isSaving, modifier = Modifier.padding(start = 16.dp)) { Text("返回") } }
        OutlinedTextField(state.displayName, viewModel::updateDisplayName, enabled = !state.isSaving, label = { Text("显示名称") })
        OutlinedTextField(state.baseUrl, viewModel::updateBaseUrl, enabled = !state.isSaving, label = { Text("Base URL") })
        OutlinedTextField(state.modelName, viewModel::updateModelName, enabled = !state.isSaving, label = { Text("模型名称") })
        OutlinedTextField(apiKeyInput, { apiKeyInput = it; viewModel.updateApiKey(it) }, enabled = !state.isSaving, label = { Text("API Key") }, placeholder = { Text(state.apiKeyMasked) }, visualTransformation = PasswordVisualTransformation())
        Row {
            Checkbox(state.supportsVision, viewModel::updateSupportsVision, enabled = !state.isSaving)
            Text("支持图片")
            Checkbox(state.supportsFiles, viewModel::updateSupportsFiles, enabled = !state.isSaving)
            Text("支持文本文件")
        }
        Text("API 协议", modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ProviderProtocol.values().forEach { protocol ->
                val selected = state.protocol == protocol
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            enabled = !state.isSaving,
                            role = Role.RadioButton,
                            onClick = { viewModel.updateProtocol(protocol) },
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = null,
                        enabled = !state.isSaving,
                    )
                    Text(
                        protocol.displayName(),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        state.validationErrors.forEach { Text(it) }
        state.saveError?.let { Text(it) }
        Button(onClick = { viewModel.save(makeDefault = true) }, enabled = !state.isSaving) { Text(if (inDialog) "保存并使用" else "保存并设为默认") }
        if (inDialog) {
            state.configs.forEach { config -> ConfigRow(config, state, viewModel, providerRegistry, onUse) }
        } else LazyColumn { items(state.configs, key = { it.id }) { config ->
            ConfigRow(config, state, viewModel, providerRegistry, onUse)
        } }
    }
    state.confirmDeleteId?.let { AlertDialog(onDismissRequest = { if (!state.isSaving) viewModel.cancelDelete() }, title = { Text("删除配置？") }, text = { Text("此操作会删除 API Key") }, confirmButton = { Button(onClick = viewModel::confirmDelete, enabled = !state.isSaving) { Text("删除") } }, dismissButton = { Button(onClick = viewModel::cancelDelete, enabled = !state.isSaving) { Text("取消") } }) }
}

@Composable
private fun ConfigRow(config: com.example.agentchat.domain.model.ModelConfig, state: ModelConfigUiState, viewModel: ModelConfigViewModel, providerRegistry: ProviderRegistry, onUse: (com.example.agentchat.domain.model.ModelConfig) -> Unit) {
    val operationBusy = config.id in state.operationIds
    val connectionBusy = config.id in state.connectionTestingIds
    Row(Modifier.fillMaxWidth()) { Checkbox(config.enabled, { viewModel.setEnabled(config, it) }, enabled = !operationBusy && !state.isSaving, modifier = Modifier.semantics { contentDescription = "启用模型 ${config.displayName}" }); Text(config.displayName); Button(onClick = { onUse(config) }, enabled = config.enabled && !operationBusy && !state.isSaving, modifier = Modifier.semantics { contentDescription = "使用模型 ${config.displayName}" }) { Text("使用此模型") }; Button(onClick = { viewModel.setDefault(config) }, enabled = config.enabled && !operationBusy && !state.isSaving) { Text(if (config.isDefault) "默认" else "设为默认") }; Button(onClick = { viewModel.testConnection(config, providerRegistry) }, enabled = !operationBusy && !connectionBusy && !state.isSaving) { Text(if (connectionBusy) "测试中…" else "测试连接") }; state.connectionResults[config.id]?.let { Text(connectionMessage(it)) }; Button(onClick = { viewModel.edit(config) }, enabled = !operationBusy && !connectionBusy && !state.isSaving) { Text("编辑") }; Button(onClick = { viewModel.requestDelete(config.id) }, enabled = !operationBusy && !connectionBusy && !state.isSaving) { Text("删除") } }
}

private fun connectionMessage(result: ConnectionResult) = when (result) {
    ConnectionResult.Success -> "连接成功"
    ConnectionResult.AuthenticationFailed -> "认证失败"
    ConnectionResult.NetworkFailed -> "网络失败"
    ConnectionResult.ServiceUnavailable -> "服务暂时不可用"
    ConnectionResult.ProtocolIncompatible -> "协议不兼容"
    ConnectionResult.SecretReadFailed -> "读取 API Key 失败"
    ConnectionResult.ConnectionFailure -> "连接测试失败"
}

private fun ProviderProtocol.displayName() = when (this) {
    ProviderProtocol.OPENAI_COMPATIBLE -> "OpenAI 兼容"
    ProviderProtocol.ANTHROPIC -> "Anthropic"
    ProviderProtocol.GEMINI -> "Gemini"
    ProviderProtocol.CUSTOM -> "自定义"
}
