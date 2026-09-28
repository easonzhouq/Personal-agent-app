package com.example.agentchat.ui.skill

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agentchat.data.attachment.AttachmentPicker
import com.example.agentchat.data.skill.SkillRepository
import com.example.agentchat.domain.skill.SkillParser
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SkillViewModel(
    private val repository: SkillRepository,
    private val resolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SkillUiState())
    val uiState: StateFlow<SkillUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeSkills().collect { skills -> _uiState.update { it.copy(skills = skills) } }
        }
    }

    fun importUri(uri: Uri) {
        if (_uiState.value.isImporting) return
        _uiState.update { it.copy(isImporting = true, error = null) }
        viewModelScope.launch(ioDispatcher) {
            try {
                AttachmentPicker.takePersistablePermission(resolver, uri)
                val content = resolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    reader.readLimited(SkillParser.MAX_INSTRUCTION_CHARS + 1)
                } ?: throw IOException("无法读取 Skill 文件")
                val parsed = SkillParser.parse(content).getOrElse { error -> throw error }
                repository.save(parsed)
                _uiState.update { it.copy(isImporting = false, error = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update { it.copy(isImporting = false, error = error.message ?: "Skill 导入失败") }
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            try {
                repository.setEnabled(id, enabled)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update { it.copy(error = error.message ?: "Skill 状态更新失败") }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(ioDispatcher) {
            try {
                repository.delete(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update { it.copy(error = error.message ?: "Skill 删除失败") }
            }
        }
    }

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
}
