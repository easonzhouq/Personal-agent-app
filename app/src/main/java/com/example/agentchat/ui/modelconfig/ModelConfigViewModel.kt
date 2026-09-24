package com.example.agentchat.ui.modelconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agentchat.data.config.ModelConfigRepository
import com.example.agentchat.data.secret.SecretStore
import com.example.agentchat.data.provider.ConnectionResult
import com.example.agentchat.data.provider.ProviderRegistry
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.model.ProviderProtocol
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException

data class ModelConfigUiState(
    val configs: List<ModelConfig> = emptyList(),
    val displayName: String = "",
    val baseUrl: String = "",
    val modelName: String = "",
    val protocol: ProviderProtocol = ProviderProtocol.OPENAI_COMPATIBLE,
    val enabled: Boolean = true,
    val supportsVision: Boolean = false,
    val supportsFiles: Boolean = false,
    val profilePrompt: String = "",
    val apiKeyMasked: String = "",
    val validationErrors: List<String> = emptyList(),
    val confirmDeleteId: String? = null,
    val editingId: String? = null,
    val connectionResults: Map<String, ConnectionResult> = emptyMap(),
    val connectionTestingIds: Set<String> = emptySet(),
    val saveError: String? = null,
    val operationIds: Set<String> = emptySet(),
    val isSaving: Boolean = false,
    val lastSavedConfigId: String? = null,
)

class ModelConfigViewModel(
    private val repository: ModelConfigRepository,
    private val secretStore: SecretStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ModelConfigUiState())
    val uiState: StateFlow<ModelConfigUiState> = _uiState.asStateFlow()
    private var apiKeyDraft: String? = null

    init { viewModelScope.launch { repository.observe().collect { configs -> _uiState.update { it.copy(configs = configs) } } } }

    fun updateDisplayName(value: String) = _uiState.update { it.copy(displayName = value, validationErrors = emptyList(), saveError = null) }
    fun updateBaseUrl(value: String) = _uiState.update { it.copy(baseUrl = value, validationErrors = emptyList(), saveError = null) }
    fun updateModelName(value: String) = _uiState.update { it.copy(modelName = value, validationErrors = emptyList(), saveError = null) }
    fun updateProtocol(value: ProviderProtocol) = _uiState.update { it.copy(protocol = value, validationErrors = emptyList(), saveError = null) }
    fun updateEnabled(value: Boolean) = _uiState.update { it.copy(enabled = value) }
    fun updateSupportsVision(value: Boolean) = _uiState.update { it.copy(supportsVision = value) }
    fun updateSupportsFiles(value: Boolean) = _uiState.update { it.copy(supportsFiles = value) }
    fun updateProfilePrompt(value: String) = _uiState.update { it.copy(profilePrompt = value, saveError = null) }
    fun updateApiKey(value: String) { apiKeyDraft = value; _uiState.update { it.copy(apiKeyMasked = if (value.isEmpty()) "" else "••••••••", saveError = null) } }

    fun edit(config: ModelConfig) {
        apiKeyDraft = null
        _uiState.value = ModelConfigUiState(configs = _uiState.value.configs, displayName = config.displayName, baseUrl = config.baseUrl, modelName = config.modelName, protocol = config.protocol, enabled = config.enabled, supportsVision = config.supportsVision, supportsFiles = config.supportsFiles, profilePrompt = config.profilePrompt, apiKeyMasked = "••••••••", editingId = config.id, lastSavedConfigId = null)
    }

    fun resetForm() {
        apiKeyDraft = null
        _uiState.value = ModelConfigUiState(configs = _uiState.value.configs, lastSavedConfigId = null)
    }

    fun resetState() {
        apiKeyDraft = null
        _uiState.value = ModelConfigUiState(lastSavedConfigId = null)
    }

    fun consumeLastSavedConfigId() {
        _uiState.update { it.copy(lastSavedConfigId = null) }
    }

    fun save(makeDefault: Boolean = false) {
        val state = _uiState.value
        val errors = buildList {
            if (state.displayName.isBlank()) add("请输入显示名称")
            if (!isAllowedBaseUrl(state.baseUrl.trim())) add("Base URL 必须是 HTTPS；仅允许 localhost 的 HTTP")
            if (state.modelName.isBlank()) add("请输入模型名称")
        }
        if (errors.isNotEmpty()) { _uiState.update { it.copy(validationErrors = errors) }; return }
        val config = ModelConfig(state.editingId ?: UUID.randomUUID().toString(), state.displayName.trim(), state.baseUrl.trim().trimEnd('/'), state.modelName.trim(), state.protocol, state.enabled, state.supportsVision, state.supportsFiles, profilePrompt = state.profilePrompt.trim())
        val apiKeySnapshot = apiKeyDraft
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch(ioDispatcher) {
            try {
                repository.save(config, apiKeySnapshot, makeDefault)
                apiKeyDraft = null
                _uiState.update { it.copy(apiKeyMasked = if (state.apiKeyMasked.isNotEmpty()) "••••••••" else "", editingId = config.id, validationErrors = emptyList(), saveError = null, isSaving = false, lastSavedConfigId = config.id) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update { it.copy(saveError = error.message ?: "模型配置保存失败，请重试", isSaving = false) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun requestDelete(id: String) = _uiState.update { it.copy(confirmDeleteId = id) }
    fun cancelDelete() = _uiState.update { it.copy(confirmDeleteId = null) }
    fun confirmDelete() { _uiState.value.confirmDeleteId?.let { id -> mutate(id) { repository.delete(id); _uiState.update { it.copy(confirmDeleteId = null, saveError = null) } } } }
    fun setEnabled(config: ModelConfig, enabled: Boolean) = mutate(config.id) { repository.setEnabled(config.id, enabled) }
    fun setDefault(config: ModelConfig) = mutate(config.id) { repository.setDefault(config.id) }
    fun recordConnectionResult(configId: String, result: ConnectionResult) = _uiState.update { it.copy(connectionResults = it.connectionResults + (configId to result)) }

    fun testConnection(config: ModelConfig, registry: ProviderRegistry) {
        _uiState.update { it.copy(connectionTestingIds = it.connectionTestingIds + config.id) }
        viewModelScope.launch(ioDispatcher) {
            try {
                val key = try {
                    secretStore.getApiKey(config.id).orEmpty()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    null
                }
                val result = if (key == null) ConnectionResult.SecretReadFailed else try {
                    registry.testConnection(config, key)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    ConnectionResult.ConnectionFailure
                }
                _uiState.update { state -> state.copy(connectionResults = state.connectionResults + (config.id to result)) }
            } catch (error: CancellationException) {
                throw error
            } finally {
                _uiState.update { it.copy(connectionTestingIds = it.connectionTestingIds - config.id) }
            }
        }
    }

    private fun mutate(id: String, operation: suspend () -> Unit) {
        _uiState.update { it.copy(operationIds = it.operationIds + id, saveError = null) }
        viewModelScope.launch(ioDispatcher) {
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update { it.copy(saveError = error.message ?: "模型配置操作失败，请重试") }
            } finally {
                _uiState.update { it.copy(operationIds = it.operationIds - id) }
            }
        }
    }

    private fun isAllowedBaseUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        uri.scheme.equals("https", true) || (uri.scheme.equals("http", true) && uri.host in setOf("localhost", "127.0.0.1", "::1"))
    }.getOrDefault(false)
}
