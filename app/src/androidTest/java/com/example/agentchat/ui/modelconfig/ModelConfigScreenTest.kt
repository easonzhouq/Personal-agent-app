package com.example.agentchat.ui.modelconfig

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agentchat.data.config.ModelConfigRepository
import com.example.agentchat.data.provider.ProviderRegistry
import com.example.agentchat.data.provider.ConnectionResult
import com.example.agentchat.data.secret.FakeSecretStore
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.model.ProviderProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelConfigScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun useThisModelInvokesNavigationSelectionForEnabledConfig() {
        val config = ModelConfig("cfg", "Saved model", "https://example.test", "saved", ProviderProtocol.OPENAI_COMPATIBLE)
        val viewModel = ModelConfigViewModel(FakeConfigRepository(listOf(config)), FakeSecretStore())
        var selected: ModelConfig? = null

        compose.setContent { ModelConfigScreen(viewModel, ProviderRegistry(), onUse = { selected = it }) }
        compose.onNodeWithText("使用此模型").assertIsDisplayed().performClick()

        assertEquals(config, selected)
    }

    @Test
    fun connectionResultIsRenderedForTheMatchingConfigRow() {
        val first = ModelConfig("one", "First", "https://one.test", "one", ProviderProtocol.OPENAI_COMPATIBLE)
        val second = ModelConfig("two", "Second", "https://two.test", "two", ProviderProtocol.OPENAI_COMPATIBLE)
        val viewModel = ModelConfigViewModel(FakeConfigRepository(listOf(first, second)), FakeSecretStore())
        viewModel.recordConnectionResult(first.id, ConnectionResult.AuthenticationFailed)
        viewModel.recordConnectionResult(second.id, ConnectionResult.NetworkFailed)

        compose.setContent { ModelConfigScreen(viewModel, ProviderRegistry()) }
        compose.onNodeWithText("认证失败").assertIsDisplayed()
        compose.onNodeWithText("网络失败").assertIsDisplayed()
    }

    @Test
    fun configControlsAreDisabledWhileThatConfigIsMutating() {
        val config = ModelConfig("cfg", "Busy model", "https://example.test", "saved", ProviderProtocol.OPENAI_COMPATIBLE)
        val viewModel = ModelConfigViewModel(FakeConfigRepository(listOf(config)), FakeSecretStore())
        viewModel.setEnabled(config, false)

        compose.setContent { ModelConfigScreen(viewModel, ProviderRegistry()) }

        compose.onNodeWithContentDescription("启用模型 Busy model").assertIsNotEnabled()
    }
}

private class FakeConfigRepository(initial: List<ModelConfig>) : ModelConfigRepository {
    private val values = MutableStateFlow(initial)
    override fun observe(): Flow<List<ModelConfig>> = values
    override suspend fun save(config: ModelConfig, apiKey: String?, makeDefault: Boolean) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun setDefault(id: String) = Unit
}
