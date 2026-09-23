package com.example.agentchat.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agentchat.domain.model.Conversation
import android.content.ContentResolver
import android.net.Uri
import com.example.agentchat.data.export.MarkdownExporter
import com.example.agentchat.data.export.SecretReadResult
import com.example.agentchat.data.secret.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

interface HistoryRepositoryPort {
    fun observeConversations(): Flow<List<Conversation>>
    fun observeMessages(id: String): Flow<List<com.example.agentchat.domain.model.ChatMessage>>
    suspend fun search(query: String): List<Conversation>
    suspend fun deleteConversation(id: String, draftContentUris: Set<String>)
    suspend fun clearAllLocalData(draftContentUris: Set<String>) {}
}

sealed class ExportResult {
    data object Success : ExportResult()
    data object Cancelled : ExportResult()
    data object ConversationNotFound : ExportResult()
    data object WriteFailed : ExportResult()
    data object SecretReadFailed : ExportResult()
}

data class HistoryUiState(
    val conversations: List<Conversation> = emptyList(),
    val query: String = "",
    val confirmDeleteId: String? = null,
    val exportResult: ExportResult? = null,
    val error: String? = null,
    val busyIds: Set<String> = emptySet(),
    val isSearching: Boolean = false,
    val confirmClearAll: Boolean = false,
)

class HistoryViewModel(
    private val repository: HistoryRepositoryPort,
    private val writeMarkdown: (ContentResolver, Uri, String, List<com.example.agentchat.domain.model.ChatMessage>, Set<String>) -> Unit = { resolver, destination, title, messages, secrets -> MarkdownExporter.export(resolver, destination, title, messages, secretValues = secrets) },
    private val clearAll: suspend (Set<String>) -> Unit = repository::clearAllLocalData,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()
    private var searchGeneration = 0L

    init {
        viewModelScope.launch {
            try {
                repository.observeConversations().collect { all -> _uiState.update { state -> state.copy(conversations = if (state.query.isBlank()) all else state.conversations) } }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update { it.copy(error = error.message ?: "历史加载失败") }
            }
        }
    }
    fun updateQuery(query: String) {
        val generation = ++searchGeneration
        _uiState.update { it.copy(query = query, error = null, isSearching = true) }
        viewModelScope.launch {
            try {
                val results = if (query.isBlank()) repository.observeConversations().first() else repository.search(query)
                if (generation == searchGeneration) _uiState.update { it.copy(conversations = results) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == searchGeneration) _uiState.update { it.copy(error = error.message ?: "搜索历史失败") }
            } finally {
                if (generation == searchGeneration) _uiState.update { it.copy(isSearching = false) }
            }
        }
    }
    fun requestDelete(id: String) = _uiState.update { it.copy(confirmDeleteId = id) }
    fun openConversation(id: String, onOpened: (String, List<com.example.agentchat.domain.model.ChatMessage>) -> Unit) {
        _uiState.update { it.copy(busyIds = it.busyIds + id, error = null) }
        viewModelScope.launch {
            try {
                onOpened(id, repository.observeMessages(id).first())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update { it.copy(error = error.message ?: "打开会话失败") }
            } finally {
                _uiState.update { it.copy(busyIds = it.busyIds - id) }
            }
        }
    }
    fun cancelDelete() = _uiState.update { it.copy(confirmDeleteId = null) }
    fun resetState() { _uiState.value = HistoryUiState() }
    fun requestClearAll() = _uiState.update { it.copy(confirmClearAll = true, error = null) }
    fun cancelClearAll() = _uiState.update { it.copy(confirmClearAll = false) }
    fun confirmClearAll(draftContentUris: Set<String>) {
        val busyId = "__clear_all__"
        _uiState.update { it.copy(busyIds = it.busyIds + busyId, error = null) }
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { clearAll(draftContentUris) }
                _uiState.value = HistoryUiState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update { it.copy(confirmClearAll = false, error = error.message ?: "清空本地数据失败") }
            } finally {
                _uiState.update { it.copy(busyIds = it.busyIds - busyId) }
            }
        }
    }
    fun confirmDelete(draftContentUris: Set<String>) { _uiState.value.confirmDeleteId?.let { id ->
        _uiState.update { it.copy(busyIds = it.busyIds + id, error = null) }
        viewModelScope.launch {
            try {
                repository.deleteConversation(id, draftContentUris)
                _uiState.update { it.copy(confirmDeleteId = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update { it.copy(error = error.message ?: "删除会话失败") }
            } finally {
                _uiState.update { it.copy(busyIds = it.busyIds - id) }
            }
        }
    } }
    fun reportExportCancelled() { _uiState.update { it.copy(exportResult = ExportResult.Cancelled) } }
    fun reportExportSecretReadFailed() { _uiState.update { it.copy(exportResult = ExportResult.SecretReadFailed) } }
    fun exportConversationWithSecrets(id: String, resolver: ContentResolver, destination: Uri, secretStore: SecretStore, configIds: Collection<String>) {
        viewModelScope.launch {
            try {
                when (val secrets = MarkdownExporter.readSecrets(secretStore, configIds)) {
                    SecretReadResult.Failed -> reportExportSecretReadFailed()
                    is SecretReadResult.Success -> exportConversation(id, resolver, destination, secrets.values)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update { it.copy(error = error.message ?: "导出失败") }
            }
        }
    }
    fun exportConversation(id: String, resolver: ContentResolver, destination: Uri, secretValues: Set<String>) {
        viewModelScope.launch {
            try {
                val conversation = _uiState.value.conversations.firstOrNull { it.id == id }
                    ?: repository.observeConversations().first().firstOrNull { it.id == id }
                if (conversation == null) { _uiState.update { it.copy(exportResult = ExportResult.ConversationNotFound) }; return@launch }
                val result = try {
                    writeMarkdown(resolver, destination, conversation.title, repository.observeMessages(id).first(), secretValues)
                    ExportResult.Success
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    ExportResult.WriteFailed
                }
                _uiState.update { it.copy(exportResult = result) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update { it.copy(error = error.message ?: "导出失败") }
            }
        }
    }
}
