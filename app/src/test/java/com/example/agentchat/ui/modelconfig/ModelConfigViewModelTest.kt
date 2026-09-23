package com.example.agentchat.ui.modelconfig

import com.example.agentchat.data.secret.FakeSecretStore
import com.example.agentchat.data.config.ModelConfigRepository
import com.example.agentchat.data.provider.ConnectionResult
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.model.ProviderProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun invalidFormIsReportedWithoutPersisting() = runTest {
        val repository = FakeModelConfigRepository()
        val viewModel = ModelConfigViewModel(repository, FakeSecretStore(), dispatcher)

        viewModel.updateDisplayName("")
        viewModel.updateBaseUrl("not a url")
        viewModel.updateModelName("")
        viewModel.save()

        assertTrue(viewModel.uiState.value.validationErrors.isNotEmpty())
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun apiKeyIsMaskedAndNeverAppearsInUiState() = runTest {
        val repository = FakeModelConfigRepository()
        val viewModel = ModelConfigViewModel(repository, FakeSecretStore(), dispatcher)

        viewModel.updateApiKey("super-secret-key")

        assertEquals("••••••••", viewModel.uiState.value.apiKeyMasked)
        assertFalse(viewModel.uiState.value.toString().contains("super-secret-key"))
    }

    @Test
    fun savingDefaultConfigurationClearsPreviousDefault() = runTest {
        val repository = FakeModelConfigRepository()
        val viewModel = ModelConfigViewModel(repository, FakeSecretStore(), dispatcher)
        viewModel.updateDisplayName("OpenAI")
        viewModel.updateBaseUrl("https://api.example.com")
        viewModel.updateModelName("gpt")
        viewModel.updateProtocol(ProviderProtocol.OPENAI_COMPATIBLE)
        viewModel.updateApiKey("key")
        viewModel.save(makeDefault = true)

        assertTrue(repository.saved.single().isDefault)
        assertEquals(1, repository.saved.count { it.isDefault })
    }

    @Test
    fun editingWithoutChangingKeyDoesNotWriteMaskedValue() = runTest {
        val repository = FakeModelConfigRepository()
        val existing = ModelConfig("existing", "OpenAI", "https://api.example.com", "gpt", ProviderProtocol.OPENAI_COMPATIBLE)
        repository.save(existing, "real-secret", false)
        val viewModel = ModelConfigViewModel(repository, FakeSecretStore(), dispatcher)

        viewModel.edit(existing)
        viewModel.updateDisplayName("Renamed")
        viewModel.save()

        assertEquals(listOf("real-secret"), repository.apiKeyWrites)
        assertEquals("Renamed", repository.saved.single().displayName)
    }

    @Test
    fun secretReadFailureClearsTestingAndShowsConfigScopedResult() = runTest {
        val repository = FakeModelConfigRepository()
        val failingStore = object : com.example.agentchat.data.secret.SecretStore {
            override suspend fun putApiKey(configId: String, apiKey: String) = Unit
            override suspend fun getApiKey(configId: String): String? = error("decrypt failed")
            override suspend fun deleteApiKey(configId: String) = Unit
            override suspend fun clearAllApiKeys() = Unit
            override suspend fun snapshotApiKeys() = com.example.agentchat.data.secret.ApiKeySnapshot(emptyMap())
            override suspend fun restoreApiKeys(snapshot: com.example.agentchat.data.secret.ApiKeySnapshot) = Unit
        }
        val config = ModelConfig("config-a", "A", "https://example.test", "model", ProviderProtocol.OPENAI_COMPATIBLE)
        val viewModel = ModelConfigViewModel(repository, failingStore, dispatcher)

        viewModel.testConnection(config, com.example.agentchat.data.provider.ProviderRegistry())
        advanceUntilIdle()

        assertEquals(ConnectionResult.SecretReadFailed, viewModel.uiState.value.connectionResults[config.id])
        assertTrue(config.id !in viewModel.uiState.value.connectionTestingIds)
    }

    @Test
    fun ordinaryOperationFailureIsVisibleAndBusyStateIsCleared() = runTest {
        val repository = FakeModelConfigRepository().apply { failEnabled = true }
        val viewModel = ModelConfigViewModel(repository, FakeSecretStore(), dispatcher)
        val config = ModelConfig("config-a", "A", "https://example.test", "model", ProviderProtocol.OPENAI_COMPATIBLE)

        viewModel.setEnabled(config, false)
        advanceUntilIdle()

        assertEquals("enabled failed", viewModel.uiState.value.saveError)
        assertTrue(config.id !in viewModel.uiState.value.operationIds)
    }
}

private class FakeModelConfigRepository : ModelConfigRepository {
    private val state = MutableStateFlow<List<ModelConfig>>(emptyList())
    val saved = mutableListOf<ModelConfig>()
    val apiKeyWrites = mutableListOf<String>()
    var failEnabled = false

    override fun observe(): Flow<List<ModelConfig>> = state
    override suspend fun save(config: ModelConfig, apiKey: String?, makeDefault: Boolean) {
        val normalized = if (makeDefault) config.copy(isDefault = true) else config
        saved.removeAll { it.id == config.id }
        if (makeDefault) {
            saved.replaceAll { it.copy(isDefault = false) }
        }
        saved += normalized
        if (apiKey != null) apiKeyWrites += apiKey
        state.value = saved.toList()
    }
    override suspend fun delete(id: String) = Unit
    override suspend fun setEnabled(id: String, enabled: Boolean) { if (failEnabled) error("enabled failed") }
    override suspend fun setDefault(id: String) = Unit
}
