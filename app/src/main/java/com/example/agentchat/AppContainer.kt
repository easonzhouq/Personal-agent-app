package com.example.agentchat

import android.content.Context
import androidx.room.Room
import com.example.agentchat.data.attachment.AttachmentReferenceCoordinator
import com.example.agentchat.data.attachment.ContentResolverAttachmentEncoder
import com.example.agentchat.data.config.ModelConfigRepository
import com.example.agentchat.data.config.RoomModelConfigRepository
import com.example.agentchat.data.db.AgentDatabase
import com.example.agentchat.data.db.LocalHistoryRepository
import com.example.agentchat.data.export.ExportController
import com.example.agentchat.data.provider.ProviderRegistry
import com.example.agentchat.data.location.DeviceLocationProvider
import com.example.agentchat.data.search.WebSearchClient
import com.example.agentchat.data.rag.KnowledgeRepository
import com.example.agentchat.data.calendar.CalendarRepository
import com.example.agentchat.data.secret.KeystoreSecretStore
import com.example.agentchat.data.secret.SecretStore
import com.example.agentchat.data.voice.VoiceInputController
import com.example.agentchat.domain.model.ChatError
import com.example.agentchat.domain.model.ChatEvent
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.provider.ModelProvider
import com.example.agentchat.ui.chat.ChatViewModel
import com.example.agentchat.ui.history.HistoryViewModel
import com.example.agentchat.ui.modelconfig.ModelConfigViewModel
import com.example.agentchat.ui.knowledge.KnowledgeViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException

/** Application-scoped production graph. Activities consume these instances and do not rebuild them. */
class AppContainer(
    context: Context,
    databaseOverride: AgentDatabase? = null,
    secretStoreOverride: SecretStore? = null,
    providerRegistryOverride: ProviderRegistry? = null,
) {
    val applicationContext: Context = context.applicationContext
    val contentResolver = applicationContext.contentResolver
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database: AgentDatabase = databaseOverride ?: Room.databaseBuilder(
        applicationContext,
        AgentDatabase::class.java,
        DATABASE_NAME,
    ).addMigrations(AgentDatabase.MIGRATION_1_2, AgentDatabase.MIGRATION_2_3, AgentDatabase.MIGRATION_3_4).build()
    val secretStore: SecretStore = secretStoreOverride ?: KeystoreSecretStore(applicationContext)
    val attachmentEncoder = ContentResolverAttachmentEncoder(contentResolver)
    val providerRegistry: ProviderRegistry = providerRegistryOverride ?: ProviderRegistry(
        contentResolver = contentResolver,
        attachmentEncoder = attachmentEncoder,
    )
    val locationProvider = DeviceLocationProvider(applicationContext)
    val webSearchClient = WebSearchClient(
        locationProvider = locationProvider::current,
        rssFeedUrls = WebSearchClient.DEFAULT_RSS_FEEDS,
    )
    val modelConfigRepository: ModelConfigRepository = RoomModelConfigRepository(database, secretStore)
    val localHistoryRepository = LocalHistoryRepository(database, contentResolver)
    val knowledgeRepository = KnowledgeRepository(database)
    val calendarRepository = CalendarRepository(applicationContext)
    val attachmentReferenceCoordinator: AttachmentReferenceCoordinator = localHistoryRepository.attachmentReferenceCoordinator()

    private val registryProvider = object : ModelProvider {
        override fun stream(config: ModelConfig, apiKey: String, messages: List<ChatMessage>): Flow<ChatEvent> =
            providerRegistry.providerFor(config)?.stream(config, apiKey, messages)
                ?: flowOf(ChatEvent.Failed(ChatError("unsupported_protocol", "当前协议暂不支持")))
    }

    val chatViewModel = ChatViewModel(
        provider = registryProvider,
        secretStore = secretStore,
        appendMessage = localHistoryRepository::appendMessage,
        updateAssistantMessage = localHistoryRepository::updateAssistantMessage,
        attachmentReferenceCoordinator = attachmentReferenceCoordinator,
        providerForConfig = { config -> providerRegistry.providerFor(config) },
        webSearch = webSearchClient::search,
        ragRetriever = { query, conversationId -> localHistoryRepository.retrieveRelevantMessages(query, conversationId) },
        knowledgeRetriever = { query -> knowledgeRepository.retrieveRelevant(query) },
        calendarContextRetriever = calendarRepository::upcomingEvents,
        cleanupScope = applicationScope,
    )
    val historyViewModel = HistoryViewModel(localHistoryRepository) { draftUris -> clearAllLocalData(draftUris) }
    val modelConfigViewModel = ModelConfigViewModel(modelConfigRepository, secretStore)
    val knowledgeViewModel = KnowledgeViewModel(knowledgeRepository, contentResolver)
    val voiceInputController = VoiceInputController(
        context = applicationContext,
        onTranscript = chatViewModel::onVoiceTranscript,
    )
    val exportController = ExportController(
        historyViewModel = historyViewModel,
        resolver = contentResolver,
        secretStore = secretStore,
        configIds = { modelConfigViewModel.uiState.value.configs.map { it.id } },
    )

    suspend fun clearAllLocalData(draftContentUris: Set<String> = emptySet()) {
        val preparation = chatViewModel.prepareForLocalDataClear()
        if (!preparation.succeeded) throw LocalDataClearException("清空失败：活动消息对账失败，Room 数据保留", preparation.error ?: IllegalStateException("reconciliation failed"))
        val allDraftUris = draftContentUris + preparation.draftUris
        voiceInputController.releaseAndWait()
        val snapshot = try {
            secretStore.snapshotApiKeys()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw LocalDataClearException("清空失败：无法创建 API Key 内存快照，Room 数据保留", error)
        }
        try {
            secretStore.clearAllApiKeys()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { secretStore.restoreApiKeys(snapshot) }
            } catch (restoreError: Throwable) {
                error.addSuppressed(restoreError)
                throw LocalDataClearException("严重错误：API Key 清除失败且恢复失败，Room 数据保留", error)
            }
            throw LocalDataClearException("清空失败：API Key 未清除，Room 数据保留", error)
        }
        try {
            localHistoryRepository.clearAllLocalData(allDraftUris)
            knowledgeRepository.deleteAllSources()
            database.withTransaction { database.configDao().deleteAll() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { secretStore.restoreApiKeys(snapshot) }
            } catch (restoreError: Throwable) {
                error.addSuppressed(restoreError)
                throw LocalDataClearException("严重错误：API Key 恢复失败，数据处于部分清除状态", error)
            }
            throw LocalDataClearException("本地数据清理失败：Room/附件未完成，API Key 已恢复", error)
        }
        chatViewModel.commitResetAfterLocalDataClear()
        historyViewModel.resetState()
        modelConfigViewModel.resetState()
    }

    companion object {
        private const val DATABASE_NAME = "agent-chat.db"
    }
}

class LocalDataClearException(message: String, cause: Throwable) : IllegalStateException(message, cause)
