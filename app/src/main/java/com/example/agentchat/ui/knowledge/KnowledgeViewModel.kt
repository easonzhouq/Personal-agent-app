package com.example.agentchat.ui.knowledge

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agentchat.data.attachment.AttachmentPicker
import com.example.agentchat.data.rag.KnowledgeRepository
import com.example.agentchat.data.rag.KnowledgeSource
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KnowledgeUiState(
    val sources: List<KnowledgeSource> = emptyList(),
    val isImporting: Boolean = false,
    val error: String? = null,
)

class KnowledgeViewModel(
    private val repository: KnowledgeRepository,
    private val resolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(KnowledgeUiState())
    val uiState: StateFlow<KnowledgeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeSources().collect { sources -> _uiState.update { it.copy(sources = sources) } }
        }
    }

    fun importUri(uri: Uri) {
        if (_uiState.value.isImporting) return
        _uiState.update { it.copy(isImporting = true, error = null) }
        viewModelScope.launch(ioDispatcher) {
            try {
                AttachmentPicker.takePersistablePermission(resolver, uri)
                val displayName = queryDisplayName(uri)
                val text = resolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    reader.readLimited(MAX_DOCUMENT_CHARACTERS)
                } ?: throw IOException("无法读取文档")
                if (text.length > MAX_DOCUMENT_CHARACTERS) throw IOException("文档超过 2 MB，暂不支持导入")
                repository.importDocument(
                    sourceId = UUID.nameUUIDFromBytes(uri.toString().toByteArray(Charsets.UTF_8)).toString(),
                    displayName = displayName,
                    contentUri = uri.toString(),
                    content = text,
                )
                _uiState.update { it.copy(isImporting = false, error = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update { it.copy(isImporting = false, error = error.message ?: "知识文档导入失败") }
            }
        }
    }

    fun deleteSource(sourceId: String) {
        viewModelScope.launch(ioDispatcher) {
            try {
                repository.deleteSource(sourceId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update { it.copy(error = error.message ?: "知识文档删除失败") }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "未命名文档"

    private fun java.io.Reader.readLimited(limit: Int): String {
        val result = StringBuilder()
        val buffer = CharArray(8_192)
        while (result.length <= limit) {
            val count = read(buffer)
            if (count < 0) break
            result.append(buffer, 0, count)
        }
        return result.toString()
    }

    private companion object {
        const val MAX_DOCUMENT_CHARACTERS = 2_000_000
    }
}
