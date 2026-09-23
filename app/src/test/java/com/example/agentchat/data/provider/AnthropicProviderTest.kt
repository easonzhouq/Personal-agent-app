package com.example.agentchat.data.provider

import com.example.agentchat.domain.model.ChatEvent
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.MessageStatus
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.model.ProviderProtocol
import com.example.agentchat.domain.model.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnthropicProviderTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun postsMessagesRequestAndStreamsTextDelta() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: message_start\n" +
                        "data: {\"type\":\"message_start\"}\n\n" +
                        "event: content_block_delta\n" +
                        "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}\n\n" +
                        "event: message_stop\n" +
                        "data: {\"type\":\"message_stop\"}\n\n",
                ),
        )

        val events = AnthropicProvider().stream(config(), "secret-key", listOf(message("ping"))).toList()
        val request = server.takeRequest()

        assertEquals("POST", request.method)
        assertEquals("/v1/messages", request.path)
        assertEquals("secret-key", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        assertTrue(events.contains(ChatEvent.Delta("hello")))
        assertTrue(events.any { it is ChatEvent.Completed })
    }

    private fun config() = ModelConfig(
        id = "claude",
        displayName = "Claude",
        baseUrl = server.url("/v1").toString(),
        modelName = "claude-3-5-sonnet",
        protocol = ProviderProtocol.ANTHROPIC,
    )

    private fun message(text: String) = ChatMessage(
        id = "user",
        conversationId = "conversation",
        role = Role.USER,
        text = text,
        status = MessageStatus.COMPLETED,
    )
}
