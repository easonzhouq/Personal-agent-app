package com.example.agentchat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agentchat.data.secret.SecretStore
import com.example.agentchat.data.attachment.AttachmentValidator
import com.example.agentchat.data.attachment.AttachmentValidationReason
import com.example.agentchat.data.attachment.AttachmentReferenceCoordinator
import com.example.agentchat.data.attachment.userMessage
import com.example.agentchat.domain.model.Attachment
import com.example.agentchat.domain.model.ChatEvent
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.MessageStatus
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.model.Role
import com.example.agentchat.domain.provider.ModelProvider
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RequestReconciliationResult(
    val draftUris: Set<String>,
    val succeeded: Boolean,
    val error: Throwable? = null,
)

class ChatViewModel(
    private val provider: ModelProvider,
    private val secretStore: SecretStore,
    private val appendMessage: suspend (ChatMessage) -> ChatMessage,
    private val updateAssistantMessage: suspend (id: String, text: String, status: MessageStatus) -> Unit,
    initialMessages: List<ChatMessage> = emptyList(),
    initialConfig: ModelConfig? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val attachmentReferenceCoordinator: AttachmentReferenceCoordinator? = null,
    private val providerForConfig: (ModelConfig) -> ModelProvider? = { provider },
    private val cleanupScope: CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO),
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        ChatUiState(
            messages = initialMessages,
            selectedModel = initialConfig,
            selectedConfigId = initialConfig?.id,
        ),
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val persistenceMutex = Mutex()
    private val reconciliationMutex = Mutex()
    private var generation = 0L
    private var activeRequest: ActiveRequest? = null
    private var streamJob: Job? = null
    private var reconciliationBlocked = false
    private var pendingReconciliation: ActiveRequest? = null

    var lastUpdatedAssistantId: String? = null
        private set

    fun onIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.DraftChanged -> _uiState.value = _uiState.value.copy(draft = intent.value, error = null)
            is ChatIntent.AttachmentsSelected -> addAttachments(intent)
            ChatIntent.Send -> send()
            ChatIntent.Stop -> stop()
            ChatIntent.Retry -> retry(null)
            is ChatIntent.RetryAssistant -> retry(intent.assistantId)
            is ChatIntent.RemoveAttachment -> removeDraftAttachment(intent.attachmentId)
        }
    }

    fun onVoiceTranscript(text: String) {
        onIntent(ChatIntent.DraftChanged(text))
    }

    fun setModel(config: ModelConfig, modelProvider: ModelProvider? = providerForConfig(config)) {
        if (!config.enabled) {
            clearModel("当前模型已禁用，请选择其他模型")
            return
        }
        _uiState.value = _uiState.value.copy(selectedModel = config, selectedConfigId = config.id)
        selectedProvider = modelProvider
    }

    fun clearModel(message: String = "当前模型不可用，请选择其他模型") {
        selectedProvider = null
        _uiState.value = _uiState.value.copy(selectedModel = null, selectedConfigId = null, error = message)
    }

    fun loadConversation(conversationId: String, messages: List<ChatMessage>) {
        viewModelScope.launch {
            val reconciliation = cancelActiveRequestAndReconcile()
            if (!reconciliation.succeeded) return@launch
            val draftUris = reconciliation.draftUris
            withContext(NonCancellable) { draftUris.forEach { uri -> attachmentReferenceCoordinator?.releaseIfUnreferenced(uri, emptySet()) } }
            _uiState.value = _uiState.value.copy(
                conversationId = conversationId,
                messages = messages,
                draft = "",
                attachments = emptyList(),
                isStreaming = false,
                error = null,
            )
        }
    }

    suspend fun startNewConversation() {
        val reconciliation = cancelActiveRequestAndReconcile()
        if (!reconciliation.succeeded) return
        val uris = reconciliation.draftUris
        withContext(NonCancellable) {
            uris.forEach { uri -> attachmentReferenceCoordinator?.releaseIfUnreferenced(uri, emptySet()) }
        }
        _uiState.value = _uiState.value.copy(
            conversationId = UUID.randomUUID().toString(),
            messages = emptyList(),
            draft = "",
            attachments = emptyList(),
            isStreaming = false,
            error = null,
        )
    }

    suspend fun prepareForLocalDataClear(): RequestReconciliationResult = cancelActiveRequestAndReconcile()

    suspend fun retryReconciliation(): Boolean = cancelActiveRequestAndReconcile().succeeded

    private suspend fun cancelActiveRequestAndReconcile(): RequestReconciliationResult = reconciliationMutex.withLock {
        val state = _uiState.value
        val request = activeRequest ?: pendingReconciliation
        val uris = state.attachments.map { it.contentUri }.toSet()
        val job = streamJob
        var reconciliationError: Throwable? = null
        try {
            generation++
            request?.cancelled = true
            job?.cancel()
            withContext(NonCancellable) {
                job?.cancelAndJoin()
                persistenceMutex.withLock {
                    val assistantId = request?.assistantId
                    val userId = request?.userMessageId
                    try {
                        if (assistantId != null) {
                            val text = _uiState.value.messages.firstOrNull { it.id == assistantId }?.text.orEmpty()
                            withContext(ioDispatcher) { updateAssistantMessage(assistantId, text, MessageStatus.CANCELLED) }
                        } else if (userId != null) {
                            val text = _uiState.value.messages.firstOrNull { it.id == userId }?.text ?: request?.originalDraft.orEmpty()
                            withContext(ioDispatcher) { updateAssistantMessage(userId, text, MessageStatus.FAILED) }
                        }
                    } catch (error: CancellationException) {
                        reconciliationError = error
                    } catch (error: Throwable) {
                        reconciliationError = error
                    }
                }
            }
        } catch (error: CancellationException) {
            reconciliationError = error
        } catch (error: Throwable) {
            reconciliationError = error
        } finally {
            val assistantId = request?.assistantId
            val userId = request?.userMessageId
            val reconciledId = assistantId ?: userId
            val reconciledStatus = if (assistantId != null) MessageStatus.CANCELLED else MessageStatus.FAILED
            if (reconciliationError == null && reconciledId != null) {
                _uiState.value = _uiState.value.copy(messages = _uiState.value.messages.map { message ->
                    if (message.id == reconciledId) message.copy(status = reconciledStatus) else message
                })
            }
            activeRequest = null
            streamJob = null
            reconciliationBlocked = reconciliationError != null
            pendingReconciliation = if (reconciliationError == null) null else request
            _uiState.value = _uiState.value.copy(isStreaming = false, error = reconciliationError?.message)
        }
        RequestReconciliationResult(uris, reconciliationError == null, reconciliationError)
    }

    fun commitResetAfterLocalDataClear() {
        _uiState.value = ChatUiState()
    }

    fun draftAttachmentUris(): Set<String> = _uiState.value.attachments.map { it.contentUri }.toSet()

    private var selectedProvider: ModelProvider? = provider

    private fun removeDraftAttachment(attachmentId: String) {
        val state = _uiState.value
        val removed = state.attachments.firstOrNull { it.id == attachmentId } ?: return
        val remaining = state.attachments.filterNot { it.id == attachmentId }
        _uiState.value = state.copy(attachments = remaining)
        viewModelScope.launch { releaseIfUnreferenced(removed, remaining) }
    }

    private suspend fun releaseIfUnreferenced(attachment: Attachment, draftAttachments: List<Attachment>) {
        attachmentReferenceCoordinator?.releaseIfUnreferenced(attachment.contentUri, draftAttachments)
    }

    private fun addAttachments(intent: ChatIntent.AttachmentsSelected) {
        val state = _uiState.value
        val existingUris = state.attachments.map { it.contentUri }.toSet()
        val merged = state.attachments.toMutableList()
        var duplicate = false
        intent.attachments.forEach { attachment ->
            if (attachment.contentUri in existingUris || merged.any { it.contentUri == attachment.contentUri }) duplicate = true
            else if (merged.size < AttachmentValidator.MAX_ATTACHMENTS) merged += attachment
        }
        val rejectedMessage = when {
            intent.rejected.isNotEmpty() -> "部分附件未添加：${intent.rejected.joinToString { it.reason.userMessage() }}"
            duplicate -> "重复附件已忽略"
            intent.attachments.size + state.attachments.size > AttachmentValidator.MAX_ATTACHMENTS -> "最多添加 5 个附件"
            else -> null
        }
        _uiState.value = state.copy(attachments = merged, error = rejectedMessage)
    }

    private fun send() {
        if (reconciliationBlocked || pendingReconciliation != null) {
            _uiState.value = _uiState.value.copy(error = _uiState.value.error ?: "请先重试消息对账")
            return
        }
        if (_uiState.value.isStreaming || activeRequest != null) return
        val state = _uiState.value
        val config = state.selectedModel
        val originalDraft = state.draft
        val text = originalDraft.trim()
        if (text.isEmpty() && state.attachments.isEmpty()) {
            _uiState.value = state.copy(error = "请输入消息")
            return
        }
        if (config == null || state.selectedConfigId.isNullOrBlank()) {
            _uiState.value = state.copy(error = "请先选择模型")
            return
        }
        AttachmentValidator.validate(state.attachments, config).reason?.let { reason ->
            _uiState.value = state.copy(error = reason.message())
            return
        }
        val token = ++generation
        val userMessageId = UUID.randomUUID().toString()
        val request = ActiveRequest(token, originalDraft, state.attachments, userMessageId = userMessageId)
        activeRequest = request
        streamJob = viewModelScope.launch {
            val userMessage = try {
                withContext(NonCancellable) {
                    withContext(ioDispatcher) {
                        appendMessage(
                            ChatMessage(
                                id = userMessageId,
                                conversationId = _uiState.value.conversationId,
                                role = Role.USER,
                                text = text,
                                attachments = state.attachments,
                                status = MessageStatus.COMPLETED,
                            ),
                        )
                    }
                }
            } catch (error: CancellationException) {
                restoreDraftIfCurrent(token, originalDraft, attachments = request.attachments)
                releaseIfCurrent(token)
                throw error
            } catch (error: Throwable) {
                restoreDraftIfCurrent(token, originalDraft, error.message ?: "消息保存失败", request.attachments)
                releaseIfCurrent(token)
                return@launch
            }
            request.userMessageId = userMessage.id
            if (request.cancelled) {
                releaseIf(request)
                return@launch
            }
            if (!isCurrent(token)) return@launch
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + userMessage,
                draft = "",
                attachments = emptyList(),
                error = null,
            )
            val assistantMessageId = UUID.randomUUID().toString()
            request.assistantId = assistantMessageId
            val assistant = try {
                withContext(NonCancellable) {
                    withContext(ioDispatcher) {
                        appendMessage(
                            ChatMessage(
                                id = assistantMessageId,
                                conversationId = userMessage.conversationId,
                                role = Role.ASSISTANT,
                                text = "",
                                status = MessageStatus.STREAMING,
                            ),
                        )
                    }
                }
            } catch (error: CancellationException) {
                restoreDraftIfCurrent(token, originalDraft, attachments = request.attachments)
                releaseIfCurrent(token)
                throw error
            } catch (error: Throwable) {
                restoreDraftIfCurrent(token, originalDraft, error.message ?: "消息保存失败", request.attachments)
                releaseIfCurrent(token)
                return@launch
            }
            request.assistantId = assistant.id
            if (request.cancelled) {
                // cancelActiveRequestAndReconcile() owns the cancellation write. The
                // append may return after Stop has entered its commit window; writing
                // here as well would race the same reconciliation and duplicate it.
                releaseIf(request)
                return@launch
            }
            if (!isCurrent(token)) return@launch
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + assistant,
                isStreaming = true,
            )
            stream(token, config, assistant)
        }
    }

    private suspend fun stream(token: Long, config: ModelConfig, assistant: ChatMessage) {
        var assistantText = ""
        var terminal = false
        try {
            val apiKey = withContext(ioDispatcher) { secretStore.getApiKey(config.id) }.orEmpty()
            val requestMessages = _uiState.value.messages.filterNot { it.id == assistant.id }
            val activeProvider = selectedProvider ?: providerForConfig(config)
            if (activeProvider == null) {
                finishAssistant(token, assistant.id, assistantText, MessageStatus.FAILED, "当前协议暂不支持")
                return
            }
            activeProvider.stream(config, apiKey, requestMessages).collect { event ->
                if (!isCurrent(token) || terminal) return@collect
                when (event) {
                    ChatEvent.Started -> Unit
                    is ChatEvent.Delta -> {
                        assistantText += event.text
                        updateAssistant(token, assistant.id, assistantText, MessageStatus.STREAMING)
                    }
                    is ChatEvent.Completed -> {
                        terminal = true
                        finishAssistant(token, assistant.id, assistantText, MessageStatus.COMPLETED)
                    }
                    is ChatEvent.Failed -> {
                        terminal = true
                        finishAssistant(token, assistant.id, assistantText, MessageStatus.FAILED, event.error.message)
                    }
                    ChatEvent.Cancelled -> {
                        terminal = true
                        finishAssistant(token, assistant.id, assistantText, MessageStatus.CANCELLED)
                    }
                }
            }
            if (!terminal && isCurrent(token)) {
                terminal = true
                finishAssistant(token, assistant.id, assistantText, MessageStatus.FAILED, UNEXPECTED_STREAM_END)
            }
        } catch (error: CancellationException) {
            val request = activeRequest
            if (request != null && isCurrent(token)) {
                withContext(NonCancellable) {
                    persistCancelled(assistant.id, assistantText)
                }
            }
            throw error
        } catch (error: Throwable) {
            if (isCurrent(token)) {
                try {
                    finishAssistant(token, assistant.id, assistantText, MessageStatus.FAILED, error.message ?: "请求失败")
                } catch (persistenceError: Throwable) {
                    cleanupFailedRequest(token, assistant.id, assistantText, persistenceError.message ?: "消息保存失败")
                }
            }
        } finally {
            if (isCurrent(token)) {
                activeRequest = null
                _uiState.value = _uiState.value.copy(isStreaming = false)
                streamJob = null
            }
        }
    }

    private suspend fun updateAssistant(token: Long, id: String, text: String, status: MessageStatus, error: String? = null) {
        persistenceMutex.withLock {
            if (!isCurrent(token)) return
            withContext(ioDispatcher) { updateAssistantMessage(id, text, status) }
            if (!isCurrent(token)) return
            lastUpdatedAssistantId = id
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages.map { if (it.id == id) it.copy(text = text, status = status) else it },
                draft = if (status == MessageStatus.FAILED || status == MessageStatus.CANCELLED) {
                    activeRequest?.originalDraft ?: _uiState.value.draft
                } else _uiState.value.draft,
                attachments = if (status == MessageStatus.FAILED || status == MessageStatus.CANCELLED) {
                    activeRequest?.attachments ?: _uiState.value.attachments
                } else _uiState.value.attachments,
                error = error,
            )
        }
    }

    private suspend fun finishAssistant(token: Long, id: String, text: String, status: MessageStatus, error: String? = null) =
        updateAssistant(token, id, text, status, error)

    private fun stop() {
        val request = activeRequest ?: return
        val pendingDraft = request.originalDraft
        val pendingAttachments = request.attachments
        viewModelScope.launch {
            cancelActiveRequestAndReconcile()
            _uiState.value = _uiState.value.copy(draft = pendingDraft, attachments = pendingAttachments, isStreaming = false)
        }
    }

    private fun invalidateActiveRequest(): Long {
        generation++
        activeRequest?.cancelled = true
        streamJob?.cancel()
        activeRequest = null
        streamJob = null
        return generation
    }

    private fun retry(assistantId: String?) {
        if (_uiState.value.isStreaming || activeRequest != null) return
        val state = _uiState.value
        val failed = state.messages.firstOrNull { it.id == assistantId && it.role == Role.ASSISTANT && it.status == MessageStatus.FAILED }
            ?: state.messages.lastOrNull { it.role == Role.ASSISTANT && it.status == MessageStatus.FAILED }
            ?: return
        val config = state.selectedModel ?: return
        val originalDraft = state.draft
        val retryAttachments = state.attachments.ifEmpty {
            state.messages.lastOrNull { it.role == Role.USER }?.attachments.orEmpty()
        }
        val token = ++generation
        val request = ActiveRequest(token, originalDraft, retryAttachments, assistantId = failed.id)
        activeRequest = request
        streamJob = viewModelScope.launch {
            try {
                _uiState.value = state.copy(
                    isStreaming = true,
                    error = null,
                    messages = state.messages.map { if (it.id == failed.id) it.copy(text = "", status = MessageStatus.STREAMING) else it },
                )
                withContext(ioDispatcher) { updateAssistantMessage(failed.id, "", MessageStatus.STREAMING) }
                if (isCurrent(token)) stream(token, config, failed)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                cleanupFailedRequest(token, failed.id, failed.text, error.message ?: "消息保存失败")
            } finally {
                if (isCurrent(token)) {
                    activeRequest = null
                    _uiState.value = _uiState.value.copy(isStreaming = false)
                    streamJob = null
                }
            }
        }
    }

    private fun restoreDraftIfCurrent(token: Long, draft: String, error: String? = null, attachments: List<Attachment>? = null) {
        if (isCurrent(token)) _uiState.value = _uiState.value.copy(draft = draft, error = error, attachments = attachments ?: _uiState.value.attachments)
    }

    private fun releaseIfCurrent(token: Long) {
        activeRequest?.takeIf { it.token == token }?.let {
            activeRequest = null
            streamJob = null
        }
    }

    private fun releaseIf(request: ActiveRequest) {
        if (activeRequest === request) {
            activeRequest = null
            streamJob = null
        }
    }

    private fun cleanupFailedRequest(token: Long, id: String, text: String, error: String) {
        val request = activeRequest
        if (request != null && isCurrent(token)) {
            activeRequest = null
            streamJob = null
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages.map {
                    if (it.id == id) it.copy(text = text, status = MessageStatus.FAILED) else it
                },
                draft = request.originalDraft,
                attachments = request.attachments,
                isStreaming = false,
                error = error,
            )
        }
    }

    private suspend fun persistCancelled(id: String, text: String) {
        withContext(NonCancellable) {
            persistenceMutex.withLock {
                withContext(ioDispatcher) { updateAssistantMessage(id, text, MessageStatus.CANCELLED) }
            }
        }
    }

    private fun isCurrent(token: Long): Boolean = generation == token && activeRequest?.token == token && activeRequest?.cancelled == false

    override fun onCleared() {
        generation++
        activeRequest?.cancelled = true
        val request = activeRequest
        val assistantId = request?.assistantId
        val assistantText = assistantId?.let { id -> _uiState.value.messages.firstOrNull { it.id == id }?.text }.orEmpty()
        streamJob?.cancel()
        activeRequest = null
        streamJob = null
        if (assistantId != null) {
            cleanupScope.launch(NonCancellable) {
                persistenceMutex.withLock {
                    try {
                        updateAssistantMessage(assistantId, assistantText, MessageStatus.CANCELLED)
                    } catch (_: Throwable) {
                        // Lifecycle cleanup is best effort; repository work remains serialized.
                    }
                }
            }
        }
        super.onCleared()
    }

    private data class ActiveRequest(
        val token: Long,
        val originalDraft: String,
        val attachments: List<Attachment> = emptyList(),
        var cancelled: Boolean = false,
        var userMessageId: String? = null,
        var assistantId: String? = null,
    )

    companion object {
        private const val CONVERSATION_ID = "default-conversation"
        private const val UNEXPECTED_STREAM_END = "模型流意外结束"
    }
}

private fun AttachmentValidationReason.message() = when (this) {
    AttachmentValidationReason.EMPTY_URI -> "附件 URI 为空"
    AttachmentValidationReason.UNKNOWN_MIME -> "附件类型不支持"
    AttachmentValidationReason.DUPLICATE_URI -> "不能重复添加同一附件"
    AttachmentValidationReason.SIZE_UNAVAILABLE -> "无法读取附件大小"
    AttachmentValidationReason.TOO_LARGE -> "附件不能超过 10 MiB"
    AttachmentValidationReason.TOO_MANY -> "最多添加 5 个附件"
    AttachmentValidationReason.MODEL_UNSUPPORTED -> "当前模型不支持此附件类型"
}
