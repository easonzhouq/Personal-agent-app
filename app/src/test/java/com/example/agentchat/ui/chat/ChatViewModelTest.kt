package com.example.agentchat.ui.chat

import com.example.agentchat.MainDispatcherRule
import com.example.agentchat.data.secret.SecretStore
import com.example.agentchat.data.attachment.AttachmentReferenceCoordinator
import com.example.agentchat.domain.model.ChatError
import com.example.agentchat.domain.model.Attachment
import com.example.agentchat.domain.model.ChatEvent
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.MessageStatus
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.model.ProviderProtocol
import com.example.agentchat.domain.model.Role
import com.example.agentchat.domain.provider.ModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatViewModelTest {
    private val ioDispatcher = StandardTestDispatcher()
    @get:Rule val mainDispatcherRule = MainDispatcherRule(ioDispatcher)
    private val config = ModelConfig("cfg", "Test model", "https://example.test", "model", ProviderProtocol.CUSTOM)

    @Test
    fun disabledConfigClearsSelectedModelAndProvider() = runTest(ioDispatcher) {
        val viewModel = ChatViewModel(FakeProvider(emptyFlow()), FakeSecrets(), { it }, { _, _, _ -> }, ioDispatcher = ioDispatcher)
        viewModel.setModel(config)
        viewModel.setModel(config.copy(enabled = false))

        assertEquals(null, viewModel.uiState.value.selectedModel)
        assertEquals("当前模型已禁用，请选择其他模型", viewModel.uiState.value.error)
    }

    @Test
    fun switchedDefaultConfigIsUsedForTheNextSend() = runTest(ioDispatcher) {
        var used: ModelConfig? = null
        val selected = ModelConfig("default", "Remote", "https://saved.example", "saved-model", ProviderProtocol.CUSTOM)
        val provider = object : ModelProvider {
            override fun stream(config: ModelConfig, apiKey: String, messages: List<ChatMessage>): Flow<ChatEvent> {
                used = config
                return flowOfEvents(ChatEvent.Completed())
            }
        }
        val viewModel = ChatViewModel(provider, FakeSecrets(), { it }, { _, _, _ -> }, ioDispatcher = ioDispatcher)
        viewModel.setModel(selected, provider)
        viewModel.onIntent(ChatIntent.DraftChanged("hello"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()

        assertEquals("https://saved.example", used?.baseUrl)
        assertEquals("saved-model", used?.modelName)
    }

    @Test
    fun searchFailureDoesNotBlockProviderResponse() = runTest(ioDispatcher) {
        var providerCalls = 0
        val provider = object : ModelProvider {
            override fun stream(config: ModelConfig, apiKey: String, messages: List<ChatMessage>): Flow<ChatEvent> {
                providerCalls++
                return flowOfEvents(ChatEvent.Delta("answer"), ChatEvent.Completed())
            }
        }
        val viewModel = ChatViewModel(
            provider = provider,
            secretStore = FakeSecrets(),
            appendMessage = { it },
            updateAssistantMessage = { _, _, _ -> },
            ioDispatcher = ioDispatcher,
            initialConfig = config,
            webSearch = { com.example.agentchat.data.search.WebSearchResult(failure = "search offline") },
        )

        viewModel.onIntent(ChatIntent.DraftChanged("近期天气如何"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()

        assertEquals(1, providerCalls)
        assertEquals(MessageStatus.COMPLETED, viewModel.uiState.value.messages.last().status)
        assertEquals("answer", viewModel.uiState.value.messages.last().text)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun retrievedKnowledgeIsIncludedWithSourceInProviderContext() = runTest(ioDispatcher) {
        var captured: List<ChatMessage> = emptyList()
        val provider = object : ModelProvider {
            override fun stream(config: ModelConfig, apiKey: String, messages: List<ChatMessage>): Flow<ChatEvent> {
                captured = messages
                return flowOfEvents(ChatEvent.Completed())
            }
        }
        val viewModel = ChatViewModel(
            provider = provider,
            secretStore = FakeSecrets(),
            appendMessage = { it },
            updateAssistantMessage = { _, _, _ -> },
            ioDispatcher = ioDispatcher,
            initialConfig = config,
            knowledgeRetriever = {
                listOf(
                    com.example.agentchat.data.rag.KnowledgeChunk(
                        id = "chunk-1",
                        sourceId = "source-1",
                        sourceName = "guide.md",
                        chunkIndex = 0,
                        text = "Open-Meteo 用于天气查询。",
                    ),
                )
            },
        )

        viewModel.onIntent(ChatIntent.DraftChanged("天气怎么查"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()

        val context = captured.first { it.role == Role.SYSTEM }.text
        assertTrue(context.contains("知识库"))
        assertTrue(context.contains("guide.md"))
        assertTrue(context.contains("Open-Meteo"))
    }

    @Test
    fun authorizedCalendarContextIsIncludedForScheduleQuestions() = runTest(ioDispatcher) {
        var captured: List<ChatMessage> = emptyList()
        val provider = object : ModelProvider {
            override fun stream(config: ModelConfig, apiKey: String, messages: List<ChatMessage>): Flow<ChatEvent> {
                captured = messages
                return flowOfEvents(ChatEvent.Completed())
            }
        }
        val event = com.example.agentchat.data.calendar.CalendarEventSummary(
            title = "产品评审",
            startAt = java.time.ZonedDateTime.parse("2026-09-25T10:00:00+08:00[Asia/Shanghai]"),
            endAt = java.time.ZonedDateTime.parse("2026-09-25T11:00:00+08:00[Asia/Shanghai]"),
            location = "会议室",
        )
        val viewModel = ChatViewModel(
            provider = provider,
            secretStore = FakeSecrets(),
            appendMessage = { it },
            updateAssistantMessage = { _, _, _ -> },
            ioDispatcher = ioDispatcher,
            initialConfig = config,
            calendarContextRetriever = { listOf(event) },
        )

        viewModel.onIntent(ChatIntent.DraftChanged("我今天有什么日程"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()

        val context = captured.first { it.role == Role.SYSTEM }.text
        assertTrue(context.contains("产品评审"))
        assertTrue(context.contains("会议室"))
    }

    @Test
    fun attachmentsSelectedMergesWithoutReplacingExistingAndReportsDuplicates() = runTest(ioDispatcher) {
        val existing = Attachment("old", "old.txt", "text/plain", 1, "content://old")
        val duplicate = existing.copy(id = "new")
        val added = Attachment("added", "new.txt", "text/plain", 1, "content://new")
        val viewModel = ChatViewModel(FakeProvider(emptyFlow()), FakeSecrets(), { it }, { _, _, _ -> }, initialConfig = config.copy(supportsFiles = true))
        viewModel.onIntent(ChatIntent.AttachmentsSelected(listOf(existing)))
        viewModel.onIntent(ChatIntent.AttachmentsSelected(listOf(duplicate, added)))
        assertEquals(listOf("content://old", "content://new"), viewModel.uiState.value.attachments.map { it.contentUri })
        assertEquals("重复附件已忽略", viewModel.uiState.value.error)
    }

    @Test
    fun removingDraftSharedWithHistoryDoesNotReleaseUntilHistoryReferenceIsGone() = runTest(ioDispatcher) {
        val attachment = Attachment("a", "shared.txt", "text/plain", 1, "content://shared")
        var historyCount = 1
        val released = mutableListOf<String>()
        val viewModel = ChatViewModel(
            FakeProvider(emptyFlow()), FakeSecrets(), { it }, { _, _, _ -> },
            initialConfig = config.copy(supportsFiles = true),
            ioDispatcher = ioDispatcher,
            attachmentReferenceCoordinator = AttachmentReferenceCoordinator({ historyCount }, { released += it }),
        )
        viewModel.onIntent(ChatIntent.AttachmentsSelected(listOf(attachment)))
        viewModel.onIntent(ChatIntent.RemoveAttachment("a"))
        advanceUntilIdle()
        assertTrue(released.isEmpty())
        historyCount = 0
        viewModel.onIntent(ChatIntent.AttachmentsSelected(listOf(attachment)))
        viewModel.onIntent(ChatIntent.RemoveAttachment("a"))
        advanceUntilIdle()
        assertEquals(listOf("content://shared"), released)
    }

    @Test
    fun attachmentMergeCapsAtFiveAndHandlesDuplicateAtBoundary() = runTest(ioDispatcher) {
        val initial = (0 until 4).map { Attachment("$it", "file$it.txt", "text/plain", 1, "content://$it") }
        val duplicate = initial.first().copy(id = "duplicate")
        val fifth = Attachment("fifth", "fifth.txt", "text/plain", 1, "content://fifth")
        val sixth = Attachment("sixth", "sixth.txt", "text/plain", 1, "content://sixth")
        val viewModel = ChatViewModel(FakeProvider(emptyFlow()), FakeSecrets(), { it }, { _, _, _ -> }, initialConfig = config.copy(supportsFiles = true))
        viewModel.onIntent(ChatIntent.AttachmentsSelected(initial))
        viewModel.onIntent(ChatIntent.AttachmentsSelected(listOf(duplicate, fifth, sixth)))
        assertEquals(5, viewModel.uiState.value.attachments.size)
        assertEquals(listOf("content://0", "content://1", "content://2", "content://3", "content://fifth"), viewModel.uiState.value.attachments.map { it.contentUri })
        assertTrue(viewModel.uiState.value.error!!.contains("重复附件"))
    }

    @Test
    fun selectingMoreThanFiveAttachmentsKeepsOnlyFirstFiveAndReportsLimit() = runTest(ioDispatcher) {
        val incoming = (0..5).map { Attachment("$it", "file$it.txt", "text/plain", 1, "content://$it") }
        val viewModel = ChatViewModel(FakeProvider(emptyFlow()), FakeSecrets(), { it }, { _, _, _ -> }, initialConfig = config.copy(supportsFiles = true))
        viewModel.onIntent(ChatIntent.AttachmentsSelected(incoming))
        assertEquals(5, viewModel.uiState.value.attachments.size)
        assertEquals("最多添加 5 个附件", viewModel.uiState.value.error)
    }

    @Test
    fun successfulSendClearsAttachmentsAfterPersistingThem() = runTest(ioDispatcher) {
        val attachment = Attachment("a", "notes.txt", "text/plain", 1, "content://notes")
        val persisted = mutableListOf<ChatMessage>()
        val released = mutableListOf<String>()
        val viewModel = ChatViewModel(FakeProvider(flowOfEvents(ChatEvent.Completed())), FakeSecrets(), { persisted += it; it }, { _, _, _ -> }, initialConfig = config.copy(supportsFiles = true), ioDispatcher = ioDispatcher, attachmentReferenceCoordinator = AttachmentReferenceCoordinator({ 0 }, { released += it }))
        viewModel.onIntent(ChatIntent.AttachmentsSelected(listOf(attachment)))
        viewModel.onIntent(ChatIntent.DraftChanged("send"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()
        assertTrue(persisted.first().attachments.single() == attachment)
        assertTrue(viewModel.uiState.value.attachments.isEmpty())
        assertTrue(released.isEmpty())
    }

    @Test
    fun failedSendRetainsDraftAndAttachments() = runTest(ioDispatcher) {
        val attachment = Attachment("a", "notes.txt", "text/plain", 1, "content://notes")
        val released = mutableListOf<String>()
        val viewModel = ChatViewModel(FakeProvider(flowOfEvents(ChatEvent.Failed(ChatError("read", "read failed")))), FakeSecrets(), { it }, { _, _, _ -> }, initialConfig = config.copy(supportsFiles = true), ioDispatcher = ioDispatcher, attachmentReferenceCoordinator = AttachmentReferenceCoordinator({ 0 }, { released += it }))
        viewModel.onIntent(ChatIntent.AttachmentsSelected(listOf(attachment)))
        viewModel.onIntent(ChatIntent.DraftChanged("keep"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()
        assertEquals("keep", viewModel.uiState.value.draft)
        assertEquals(listOf(attachment), viewModel.uiState.value.attachments)
        assertTrue(released.isEmpty())
    }

    @Test
    fun retryUsesOriginalUserAttachmentsAndReleasesOnlyAfterSuccess() = runTest(ioDispatcher) {
        val attachment = Attachment("a", "retry.txt", "text/plain", 1, "content://retry")
        val user = ChatMessage("user", "conversation", Role.USER, "retry", listOf(attachment), MessageStatus.COMPLETED)
        val failed = ChatMessage("assistant", "conversation", Role.ASSISTANT, "failed", status = MessageStatus.FAILED)
        var captured: List<ChatMessage> = emptyList()
        val provider = object : ModelProvider {
            override fun stream(config: ModelConfig, apiKey: String, messages: List<ChatMessage>): Flow<ChatEvent> {
                captured = messages
                return flowOfEvents(ChatEvent.Completed())
            }
        }
        val released = mutableListOf<Attachment>()
        val viewModel = ChatViewModel(
            provider,
            FakeSecrets(),
            { it },
            { _, _, _ -> },
            initialMessages = listOf(user, failed),
            initialConfig = config.copy(supportsFiles = true),
            ioDispatcher = ioDispatcher,
        )
        viewModel.onIntent(ChatIntent.DraftChanged("retry"))
        viewModel.onIntent(ChatIntent.Retry)
        advanceUntilIdle()
        assertEquals(listOf(attachment), captured.first { it.role == Role.USER }.attachments)
        assertTrue(released.isEmpty())
    }

    @Test
    fun sendPersistsNormalizedIdsAndStreamsInMessageOrder() = runTest(ioDispatcher) {
        val persisted = mutableListOf<ChatMessage>()
        val provider = FakeProvider(flowOfEvents(ChatEvent.Started, ChatEvent.Delta("hello"), ChatEvent.Completed()))
        val viewModel = ChatViewModel(provider, FakeSecrets(), { message ->
            val normalized = message.copy(id = "normalized-${persisted.size}")
            persisted += normalized
            normalized
        }, { _, _, _ -> }, ioDispatcher = ioDispatcher, initialConfig = config)

        viewModel.onIntent(ChatIntent.DraftChanged("hi"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()

        assertEquals(listOf(Role.USER, Role.ASSISTANT), viewModel.uiState.value.messages.map { it.role })
        assertEquals(MessageStatus.COMPLETED, viewModel.uiState.value.messages.last().status)
        assertEquals("hello", viewModel.uiState.value.messages.last().text)
        assertEquals("normalized-1", viewModel.lastUpdatedAssistantId)
        assertFalse(viewModel.uiState.value.isStreaming)
    }

    @Test
    fun everyDeltaPersistsAssistantTextAndStreamingStatus() = runTest(ioDispatcher) {
        val updates = mutableListOf<Pair<String, MessageStatus>>()
        val provider = FakeProvider(flowOfEvents(ChatEvent.Delta("a"), ChatEvent.Delta("b"), ChatEvent.Completed()))
        val viewModel = ChatViewModel(provider, FakeSecrets(), { it }, { _, text, status -> updates += text to status }, ioDispatcher = ioDispatcher, initialConfig = config)

        viewModel.onIntent(ChatIntent.DraftChanged("delta"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()

        assertEquals(listOf("a" to MessageStatus.STREAMING, "ab" to MessageStatus.STREAMING, "ab" to MessageStatus.COMPLETED), updates)
    }

    @Test
    fun providerFlowEndingWithoutTerminalEventFailsAssistant() = runTest(ioDispatcher) {
        val updates = mutableListOf<Pair<String, MessageStatus>>()
        val viewModel = ChatViewModel(
            provider = object : ModelProvider {
                override fun stream(config: ModelConfig, apiKey: String, messages: List<ChatMessage>): Flow<ChatEvent> = emptyFlow()
            },
            secretStore = FakeSecrets(),
            appendMessage = { it },
            updateAssistantMessage = { _, text, status -> updates += text to status },
            ioDispatcher = ioDispatcher,
            initialConfig = config,
        )

        viewModel.onIntent(ChatIntent.DraftChanged("empty stream"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()

        assertEquals(MessageStatus.FAILED, viewModel.uiState.value.messages.last().status)
        assertEquals("模型流意外结束", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isStreaming)
        assertEquals(MessageStatus.FAILED, updates.last().second)
    }

    @Test
    fun failedSendKeepsDraftAndRetryCanComplete() = runTest(ioDispatcher) {
        var attempt = 0
        val provider = object : ModelProvider {
            override fun stream(config: ModelConfig, apiKey: String, messages: List<ChatMessage>): Flow<ChatEvent> = flow {
                if (attempt++ == 0) emit(ChatEvent.Failed(ChatError(message = "offline", retryable = true)))
                else emit(ChatEvent.Completed())
            }
        }
        val viewModel = ChatViewModel(provider, FakeSecrets(), { it }, { _, _, _ -> }, ioDispatcher = ioDispatcher, initialConfig = config)

        viewModel.onIntent(ChatIntent.DraftChanged("retry me"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()
        assertEquals("retry me", viewModel.uiState.value.draft)
        assertEquals(MessageStatus.FAILED, viewModel.uiState.value.messages.last().status)

        viewModel.onIntent(ChatIntent.Retry)
        advanceUntilIdle()
        assertEquals(MessageStatus.COMPLETED, viewModel.uiState.value.messages.last().status)
    }

    @Test
    fun stopMarksAssistantCancelledAndDoesNotLeaveStreaming() = runTest(ioDispatcher) {
        val provider = FakeProvider(flow {
            emit(ChatEvent.Started)
            kotlinx.coroutines.awaitCancellation()
        })
        val released = mutableListOf<String>()
        val viewModel = ChatViewModel(provider, FakeSecrets(), { it }, { _, _, _ -> }, ioDispatcher = ioDispatcher, initialConfig = config, attachmentReferenceCoordinator = AttachmentReferenceCoordinator({ 0 }, { released += it }))

        viewModel.onIntent(ChatIntent.DraftChanged("stop"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()
        viewModel.onIntent(ChatIntent.Stop)
        advanceUntilIdle()

        assertEquals(MessageStatus.CANCELLED, viewModel.uiState.value.messages.last().status)
        assertFalse(viewModel.uiState.value.isStreaming)
        assertEquals("stop", viewModel.uiState.value.draft)
        assertTrue(viewModel.uiState.value.error == null)
        assertTrue(released.isEmpty())
    }

    @Test
    fun userMessageIsVisibleWithNormalizedIdBeforeProviderCompletes() = runTest(ioDispatcher) {
        val providerStarted = CompletableDeferred<Unit>()
        val captured = mutableListOf<List<ChatMessage>>()
        val provider = object : ModelProvider {
            override fun stream(config: ModelConfig, apiKey: String, messages: List<ChatMessage>): Flow<ChatEvent> = flow {
                captured += messages
                providerStarted.complete(Unit)
                awaitCancellation()
            }
        }
        val viewModel = ChatViewModel(provider, FakeSecrets(), {
            it.copy(id = if (it.role == Role.USER) "real-user-id" else "real-assistant-id")
        }, { _, _, _ -> }, ioDispatcher = ioDispatcher, initialConfig = config)

        viewModel.onIntent(ChatIntent.DraftChanged("visible now"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()

        assertTrue(providerStarted.isCompleted)
        assertEquals("real-user-id", viewModel.uiState.value.messages.first().id)
        assertEquals("real-user-id", captured.single().single().id)
        viewModel.onIntent(ChatIntent.Stop)
    }

    @Test
    fun repeatedSendWhileStreamingIsIgnored() = runTest(ioDispatcher) {
        val provider = FakeProvider(flow { emit(ChatEvent.Started); awaitCancellation() })
        var appendCount = 0
        val viewModel = ChatViewModel(provider, FakeSecrets(), { appendCount++; it }, { _, _, _ -> }, ioDispatcher = ioDispatcher, initialConfig = config)

        viewModel.onIntent(ChatIntent.DraftChanged("once"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()

        assertEquals(2, appendCount)
        assertEquals(2, viewModel.uiState.value.messages.size)
        viewModel.onIntent(ChatIntent.Stop)
    }

    @Test
    fun stopThenResendKeepsOldDeltaOutOfNewRequest() = runTest(ioDispatcher) {
        var call = 0
        val provider = object : ModelProvider {
            override fun stream(config: ModelConfig, apiKey: String, messages: List<ChatMessage>): Flow<ChatEvent> = flow {
                if (call++ == 0) {
                    emit(ChatEvent.Delta("old"))
                    awaitCancellation()
                } else {
                    emit(ChatEvent.Delta("new"))
                    emit(ChatEvent.Completed())
                }
            }
        }
        val viewModel = ChatViewModel(provider, FakeSecrets(), { it }, { _, _, _ -> }, ioDispatcher = ioDispatcher, initialConfig = config)

        viewModel.onIntent(ChatIntent.DraftChanged("first"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()
        viewModel.onIntent(ChatIntent.Stop)
        advanceUntilIdle()
        viewModel.onIntent(ChatIntent.DraftChanged("second"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()

        val assistants = viewModel.uiState.value.messages.filter { it.role == Role.ASSISTANT }
        assertEquals(listOf(MessageStatus.CANCELLED, MessageStatus.COMPLETED), assistants.map { it.status })
        assertEquals("new", assistants.last().text)
    }

    @Test
    fun stopDuringAssistantAppendPersistsCancelledInsteadOfStreaming() = runTest(ioDispatcher) {
        val assistantAppendStarted = CompletableDeferred<Unit>()
        val assistantAppendGate = CompletableDeferred<Unit>()
        val cancelledPersisted = CompletableDeferred<Unit>()
        val persisted = mutableListOf<ChatMessage>()
        val updates = mutableListOf<Pair<String, MessageStatus>>()
        var assistantId = ""
        val viewModel = ChatViewModel(
            provider = FakeProvider(flowOfEvents(ChatEvent.Completed())),
            secretStore = FakeSecrets(),
            appendMessage = { message ->
                if (message.role == Role.ASSISTANT) {
                    assistantId = message.id
                    assistantAppendStarted.complete(Unit)
                    withContext(NonCancellable) { assistantAppendGate.await() }
                    persisted += message
                    message
                } else {
                    persisted += message
                    message.copy(id = "user-id")
                }
            },
            updateAssistantMessage = { id, text, status ->
                updates += id to status
                val index = persisted.indexOfFirst { it.id == id }
                if (index >= 0) persisted[index] = persisted[index].copy(text = text, status = status)
                if (status == MessageStatus.CANCELLED) cancelledPersisted.complete(Unit)
            },
            ioDispatcher = ioDispatcher,
            initialConfig = config,
        )

        viewModel.onIntent(ChatIntent.DraftChanged("waiting"))
        viewModel.onIntent(ChatIntent.Send)
        runCurrent()
        assertTrue(assistantAppendStarted.isCompleted)
        viewModel.onIntent(ChatIntent.Stop)
        runCurrent()
        assistantAppendGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(cancelledPersisted.isCompleted)

        assertEquals(listOf(assistantId to MessageStatus.CANCELLED), updates)
        assertEquals(MessageStatus.CANCELLED, persisted.single { it.id == assistantId }.status)
        assertTrue(viewModel.uiState.value.messages.none { it.role == Role.ASSISTANT && it.status == MessageStatus.STREAMING })
    }

    @Test
    fun consecutiveSendsStayInOneConversation() = runTest(ioDispatcher) {
        val persisted = mutableListOf<ChatMessage>()
        val viewModel = ChatViewModel(
            provider = FakeProvider(flowOfEvents(ChatEvent.Completed())),
            secretStore = FakeSecrets(),
            appendMessage = { message -> persisted += message; message },
            updateAssistantMessage = { _, _, _ -> },
            ioDispatcher = ioDispatcher,
            initialConfig = config,
        )

        viewModel.onIntent(ChatIntent.DraftChanged("one"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()
        viewModel.onIntent(ChatIntent.DraftChanged("two"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()

        assertEquals(1, persisted.map { it.conversationId }.distinct().size)
    }

    @Test
    fun loadingHistoryInvalidatesActiveStream() = runTest(ioDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val provider = object : ModelProvider {
            override fun stream(config: ModelConfig, apiKey: String, messages: List<ChatMessage>): Flow<ChatEvent> = flow {
                emit(ChatEvent.Started)
                emit(ChatEvent.Delta("old"))
                gate.await()
                emit(ChatEvent.Completed())
            }
        }
        val history = ChatMessage("history", "loaded", Role.USER, "saved", status = MessageStatus.COMPLETED)
        val viewModel = ChatViewModel(provider, FakeSecrets(), { it }, { _, _, _ -> }, ioDispatcher = ioDispatcher, initialConfig = config)

        viewModel.onIntent(ChatIntent.DraftChanged("old"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()
        viewModel.loadConversation("loaded", listOf(history))
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("loaded", viewModel.uiState.value.conversationId)
        assertEquals(listOf(history), viewModel.uiState.value.messages)
    }

    @Test
    fun loadingHistoryPersistsActiveAssistantAsCancelledBeforeShowingHistory() = runTest(ioDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val updates = mutableListOf<Triple<String, String, MessageStatus>>()
        val provider = object : ModelProvider {
            override fun stream(config: ModelConfig, apiKey: String, messages: List<ChatMessage>): Flow<ChatEvent> = flow {
                emit(ChatEvent.Started)
                emit(ChatEvent.Delta("generated"))
                gate.await()
                emit(ChatEvent.Completed())
            }
        }
        val history = ChatMessage("saved", "loaded", Role.USER, "saved", status = MessageStatus.COMPLETED)
        val viewModel = ChatViewModel(
            provider,
            FakeSecrets(),
            appendMessage = { message -> message.copy(id = if (message.role == Role.ASSISTANT) "assistant" else "user") },
            updateAssistantMessage = { id, text, status -> updates += Triple(id, text, status) },
            ioDispatcher = ioDispatcher,
            initialConfig = config,
        )

        viewModel.onIntent(ChatIntent.DraftChanged("request"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()
        viewModel.loadConversation("loaded", listOf(history))
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(updates.contains(Triple("assistant", "generated", MessageStatus.CANCELLED)))
        assertEquals(listOf(history), viewModel.uiState.value.messages)
    }

    @Test
    fun loadingHistoryDuringUserAppendMarksPersistedUserFailedBeforeSwitch() = runTest(ioDispatcher) {
        val appendGate = CompletableDeferred<Unit>()
        val updates = mutableListOf<Pair<String, MessageStatus>>()
        val viewModel = ChatViewModel(
            FakeProvider(flowOfEvents(ChatEvent.Completed())),
            FakeSecrets(),
            appendMessage = { message ->
                if (message.role == Role.USER) {
                    appendGate.await()
                    message.copy(id = "user-id")
                } else message
            },
            updateAssistantMessage = { id, _, status -> updates += id to status },
            ioDispatcher = ioDispatcher,
            initialConfig = config,
        )

        viewModel.onIntent(ChatIntent.DraftChanged("pending"))
        viewModel.onIntent(ChatIntent.Send)
        runCurrent()
        viewModel.loadConversation("target", emptyList())
        appendGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(updates.contains("user-id" to MessageStatus.FAILED))
        assertEquals("target", viewModel.uiState.value.conversationId)
    }

    @Test
    fun reconciliationFailureKeepsMemoryMessageAndClearsStreamingState() = runTest(ioDispatcher) {
        val provider = object : ModelProvider {
            override fun stream(config: ModelConfig, apiKey: String, messages: List<ChatMessage>): Flow<ChatEvent> = flow {
                emit(ChatEvent.Started)
                awaitCancellation()
            }
        }
        val viewModel = ChatViewModel(
            provider,
            FakeSecrets(),
            appendMessage = { it.copy(id = if (it.role == Role.ASSISTANT) "assistant" else "user") },
            updateAssistantMessage = { _, _, _ -> error("repository unavailable") },
            ioDispatcher = ioDispatcher,
            initialConfig = config,
        )

        viewModel.onIntent(ChatIntent.DraftChanged("send"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()
        viewModel.onIntent(ChatIntent.Stop)
        advanceUntilIdle()

        val assistant = viewModel.uiState.value.messages.single { it.id == "assistant" }
        assertEquals(MessageStatus.STREAMING, assistant.status)
        assertFalse(viewModel.uiState.value.isStreaming)
        assertEquals("repository unavailable", viewModel.uiState.value.error)
    }

    @Test
    fun reconciliationFailureRejectsHistoryLoadAndNewConversation() = runTest(ioDispatcher) {
        val providerGate = CompletableDeferred<Unit>()
        val provider = object : ModelProvider {
            override fun stream(config: ModelConfig, apiKey: String, messages: List<ChatMessage>): Flow<ChatEvent> = flow {
                emit(ChatEvent.Started)
                providerGate.await()
                emit(ChatEvent.Completed())
            }
        }
        var fail = true
        val viewModel = ChatViewModel(provider, FakeSecrets(), { it }, { _, _, _ -> if (fail) error("cannot reconcile") }, ioDispatcher = ioDispatcher, initialConfig = config)
        viewModel.onIntent(ChatIntent.DraftChanged("active"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()
        val originalConversation = viewModel.uiState.value.conversationId

        viewModel.loadConversation("other", emptyList())
        advanceUntilIdle()
        assertEquals(originalConversation, viewModel.uiState.value.conversationId)
        assertEquals("cannot reconcile", viewModel.uiState.value.error)
        val messagesBeforeBlockedSend = viewModel.uiState.value.messages
        viewModel.onIntent(ChatIntent.DraftChanged("blocked"))
        viewModel.onIntent(ChatIntent.Send)
        assertEquals(messagesBeforeBlockedSend, viewModel.uiState.value.messages)
        assertEquals("请先重试消息对账", viewModel.uiState.value.error)

        fail = false
        assertTrue(viewModel.retryReconciliation())
        viewModel.onIntent(ChatIntent.DraftChanged("allowed"))
        viewModel.onIntent(ChatIntent.Send)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isStreaming)
        assertEquals(null, viewModel.uiState.value.error)
        providerGate.complete(Unit)
        advanceUntilIdle()

        viewModel.startNewConversation()
        assertTrue(viewModel.uiState.value.messages.isEmpty())
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun loadingHistoryReleasesDraftUriOnlyWhenHistoryHasNoReference() = runTest(ioDispatcher) {
        val attachment = Attachment("draft", "shared.txt", "text/plain", 1, "content://shared")
        var historyReferences = 1
        val released = mutableListOf<String>()
        val coordinator = AttachmentReferenceCoordinator({ historyReferences }, { released += it })
        val viewModel = ChatViewModel(
            FakeProvider(emptyFlow()),
            FakeSecrets(),
            { it },
            { _, _, _ -> },
            ioDispatcher = ioDispatcher,
            attachmentReferenceCoordinator = coordinator,
            initialConfig = config.copy(supportsFiles = true),
        )

        viewModel.onIntent(ChatIntent.AttachmentsSelected(listOf(attachment)))
        viewModel.loadConversation("history-one", emptyList())
        advanceUntilIdle()
        assertTrue(released.isEmpty())

        historyReferences = 0
        viewModel.onIntent(ChatIntent.AttachmentsSelected(listOf(attachment)))
        viewModel.loadConversation("history-two", emptyList())
        advanceUntilIdle()

        assertEquals(listOf("content://shared"), released)
    }

    @Test
    fun localDataClearPreparePreservesStateUntilCommit() = runTest(ioDispatcher) {
        val message = ChatMessage("m", "conversation", Role.USER, "keep", status = MessageStatus.COMPLETED)
        val viewModel = ChatViewModel(FakeProvider(emptyFlow()), FakeSecrets(), { it }, { _, _, _ -> }, initialMessages = listOf(message), ioDispatcher = ioDispatcher, initialConfig = config)
        viewModel.onIntent(ChatIntent.DraftChanged("draft"))

        viewModel.prepareForLocalDataClear()
        assertEquals(listOf(message), viewModel.uiState.value.messages)
        assertEquals("draft", viewModel.uiState.value.draft)

        viewModel.commitResetAfterLocalDataClear()
        assertTrue(viewModel.uiState.value.messages.isEmpty())
        assertTrue(viewModel.uiState.value.draft.isEmpty())
    }

    @Test
    fun retryPersistenceFailureRestoresDraftAndUnlocksNextRetry() = runTest(ioDispatcher) {
        val user = ChatMessage("user", "conversation", Role.USER, "retry", status = MessageStatus.COMPLETED)
        val failed = ChatMessage("assistant", "conversation", Role.ASSISTANT, "old", status = MessageStatus.FAILED)
        var failPersistence = true
        val viewModel = ChatViewModel(
            provider = FakeProvider(flowOfEvents(ChatEvent.Completed())),
            secretStore = FakeSecrets(),
            appendMessage = { it },
            updateAssistantMessage = { _, _, _ ->
                if (failPersistence) throw IllegalStateException("db down")
            },
            initialMessages = listOf(user, failed),
            ioDispatcher = ioDispatcher,
            initialConfig = config,
        )

        viewModel.onIntent(ChatIntent.DraftChanged("retry draft"))
        viewModel.onIntent(ChatIntent.Retry)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isStreaming)
        assertEquals("retry draft", viewModel.uiState.value.draft)
        assertEquals("db down", viewModel.uiState.value.error)

        failPersistence = false
        viewModel.onIntent(ChatIntent.Retry)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isStreaming)
        assertEquals(MessageStatus.COMPLETED, viewModel.uiState.value.messages.last().status)
    }
}

private class FakeProvider(private val events: Flow<ChatEvent>) : ModelProvider {
    override fun stream(config: ModelConfig, apiKey: String, messages: List<ChatMessage>): Flow<ChatEvent> = events
}

private class FakeSecrets : SecretStore {
    override suspend fun putApiKey(configId: String, apiKey: String) = Unit
    override suspend fun getApiKey(configId: String): String = "key"
    override suspend fun deleteApiKey(configId: String) = Unit
    override suspend fun clearAllApiKeys() = Unit
    override suspend fun snapshotApiKeys() = com.example.agentchat.data.secret.ApiKeySnapshot(emptyMap())
    override suspend fun restoreApiKeys(snapshot: com.example.agentchat.data.secret.ApiKeySnapshot) = Unit
}

private fun flowOfEvents(vararg events: ChatEvent): Flow<ChatEvent> = flow {
    events.forEach { emit(it) }
}
