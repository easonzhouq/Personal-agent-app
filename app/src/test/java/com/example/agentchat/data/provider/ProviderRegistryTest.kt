package com.example.agentchat.data.provider

import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.model.ProviderProtocol
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ProviderRegistryTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun mapsAuthenticationFailureWithoutExposingKey() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret-key should not be shown"))
        val result = ProviderRegistry().testConnection(config(), "secret-key")
        assertEquals(ConnectionResult.AuthenticationFailed, result)
    }

    @Test fun mapsServerFailureToServiceUnavailable() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(ConnectionResult.ServiceUnavailable, ProviderRegistry().testConnection(config(), "key"))
    }

    @Test fun malformedUrlIsProtocolIncompatibleNotNetworkFailure() = runTest {
        val result = ProviderRegistry().testConnection(config("not-a-url"), "secret-key")
        assertEquals(ConnectionResult.ProtocolIncompatible, result)
    }

    private fun config(url: String = server.url("/").toString()) = ModelConfig("id", "test", url, "model", ProviderProtocol.OPENAI_COMPATIBLE)
}
