package com.example.agentchat.data.config

import androidx.room.withTransaction
import com.example.agentchat.data.db.AgentDatabase
import com.example.agentchat.data.secret.SecretStore
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.model.ProviderProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface ModelConfigRepository {
    fun observe(): Flow<List<ModelConfig>>
    suspend fun save(config: ModelConfig, apiKey: String?, makeDefault: Boolean)
    suspend fun delete(id: String)
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun setDefault(id: String)
}

class ConfigSaveException(message: String, cause: Throwable) : IllegalStateException(message, cause)

class RoomModelConfigRepository(
    private val database: AgentDatabase,
    private val secretStore: SecretStore,
) : ModelConfigRepository {
    private val dao get() = database.configDao()
    private val operationMutex = Mutex()

    override fun observe(): Flow<List<ModelConfig>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun save(config: ModelConfig, apiKey: String?, makeDefault: Boolean) {
        operationMutex.withLock {
            val previousConfigs = dao.findAll()
            val previousKey = if (apiKey != null) secretStore.getApiKey(config.id) else null
            var keyMutationAttempted = false
            try {
                if (apiKey != null) {
                    keyMutationAttempted = true
                    if (apiKey.isBlank()) secretStore.deleteApiKey(config.id) else secretStore.putApiKey(config.id, apiKey)
                }
                database.withTransaction {
                    if (makeDefault) dao.clearDefaults()
                    dao.upsert(config.toEntity(makeDefault))
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    if (keyMutationAttempted) compensateIgnoringCancellation { restoreKey(config.id, previousKey) }
                    compensateIgnoringCancellation { restoreConfigs(previousConfigs) }
                }
                throw error
            } catch (error: Throwable) {
                if (keyMutationAttempted) compensate(error) { restoreKey(config.id, previousKey) }
                compensate(error) { restoreConfigs(previousConfigs) }
                throw ConfigSaveException("模型配置保存失败，已尝试恢复旧配置", error)
            }
        }
    }

    override suspend fun delete(id: String) {
        operationMutex.withLock {
            val previousConfigs = dao.findAll()
            val previousKey = secretStore.getApiKey(id)
            try {
                secretStore.deleteApiKey(id)
                database.withTransaction { dao.delete(id) }
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    compensateIgnoringCancellation { restoreKey(id, previousKey) }
                    compensateIgnoringCancellation { restoreConfigs(previousConfigs) }
                }
                throw error
            } catch (error: Throwable) {
                compensate(error) { restoreKey(id, previousKey) }
                compensate(error) { restoreConfigs(previousConfigs) }
                throw ConfigSaveException("模型配置删除失败，已尝试恢复旧配置", error)
            }
        }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) = operationMutex.withLock { dao.setEnabled(id, enabled) }

    override suspend fun setDefault(id: String) {
        operationMutex.withLock { database.withTransaction { dao.clearDefaults(); dao.setDefault(id) } }
    }

    private suspend fun compensate(original: Throwable, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            original.addSuppressed(error)
        }
    }

    private suspend fun compensateIgnoringCancellation(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            // Preserve the original CancellationException; compensation failures are diagnostic only.
        }
    }

    private suspend fun restoreKey(id: String, value: String?) {
        if (value == null) secretStore.deleteApiKey(id) else secretStore.putApiKey(id, value)
    }

    private suspend fun restoreConfigs(configs: List<ConfigEntity>) {
        database.withTransaction {
            dao.findAll().forEach { dao.delete(it.id) }
            configs.forEach { dao.upsert(it) }
        }
    }

    private fun ConfigEntity.toDomain() = ModelConfig(id, displayName, baseUrl, modelName, ProviderProtocol.valueOf(protocol), enabled, supportsVision, supportsFiles, isDefault, profilePrompt)
    private fun ModelConfig.toEntity(default: Boolean) = ConfigEntity(id, displayName, baseUrl, modelName, protocol.name, enabled, supportsVision, supportsFiles, default || isDefault, profilePrompt.trim())
}
