package com.example.agentchat.domain

import com.example.agentchat.domain.model.Attachment
import com.example.agentchat.domain.model.ChatError
import com.example.agentchat.domain.model.ChatEvent
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.MessageStatus
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.model.ProviderProtocol
import com.example.agentchat.domain.model.Role
import com.example.agentchat.domain.model.Usage
import com.example.agentchat.domain.tool.AgentActionProposal
import com.example.agentchat.domain.tool.AgentAction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainModelTest {
    private val json = Json { classDiscriminator = "type"; encodeDefaults = true }

    @Test
    fun modelConfigSerializesRequiredFieldsWithoutApiKey() {
        val config = ModelConfig(
            id = "openai",
            displayName = "OpenAI",
            baseUrl = "https://api.example.com",
            modelName = "gpt-test",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        )

        val encoded = json.encodeToString(config)

        assertTrue(encoded.contains("\"modelName\":\"gpt-test\""))
        assertTrue(encoded.contains("\"protocol\":\"openai_compatible\""))
        assertTrue(encoded.contains("\"enabled\":true"))
        assertTrue(encoded.contains("\"supportsVision\":false"))
        assertTrue(encoded.contains("\"supportsFiles\":false"))
        assertTrue(!encoded.contains("apiKey", ignoreCase = true))
    }

    @Test
    fun chatMessageAndAttachmentSerializeRequiredFields() {
        val message = ChatMessage(
            id = "message-1",
            conversationId = "conversation-1",
            role = Role.USER,
            text = "Hello",
            attachments = listOf(
                Attachment(
                    id = "file-1",
                    name = "notes.txt",
                    mimeType = "text/plain",
                    sizeBytes = 12,
                    contentUri = "content://notes",
                ),
            ),
            status = MessageStatus.COMPLETED,
        )

        val decoded = json.decodeFromString<ChatMessage>(json.encodeToString(message))

        assertEquals(message, decoded)
        assertTrue(json.encodeToString(message).contains("\"status\":\"completed\""))
        assertTrue(json.encodeToString(message.copy(status = MessageStatus.CANCELLED)).contains("\"status\":\"cancelled\""))
    }

    @Test
    fun chatEventsPreserveStartedDeltaCompletedOrder() {
        val events = listOf(
            ChatEvent.Started,
            ChatEvent.Delta("partial"),
            ChatEvent.Completed(Usage(promptTokens = 2, completionTokens = 3, totalTokens = 5)),
        )

        assertEquals(
            listOf(
                ChatEvent.Started,
                ChatEvent.Delta("partial"),
                ChatEvent.Completed(Usage(2, 3, 5)),
            ),
            events,
        )
        assertEquals("started", json.encodeToString<ChatEvent>(events[0]).substringAfter("\"type\":\"").substringBefore('"'))
        assertEquals("delta", json.encodeToString<ChatEvent>(events[1]).substringAfter("\"type\":\"").substringBefore('"'))
        assertEquals("completed", json.encodeToString<ChatEvent>(events[2]).substringAfter("\"type\":\"").substringBefore('"'))

        val decoded = json.decodeFromString<List<ChatEvent>>(json.encodeToString(events))
        assertEquals(events, decoded)
    }

    @Test
    fun failedAndEmptyUsageEventsRoundTrip() {
        val failed = ChatEvent.Failed(ChatError(code = "timeout", message = "Timed out"))
        val completed = ChatEvent.Completed(null)

        val failedEncoded = json.encodeToString<ChatEvent>(failed)
        val completedEncoded = json.encodeToString<ChatEvent>(completed)
        assertEquals(failed, json.decodeFromString<ChatEvent>(failedEncoded))
        assertEquals(completed, json.decodeFromString<ChatEvent>(completedEncoded))
        assertTrue(failedEncoded.contains("\"type\":\"failed\""))
        assertTrue(completedEncoded.contains("\"usage\":null"))
    }

    @Test
    fun cancelledEventHasStableSerializationName() {
        val encoded = json.encodeToString<ChatEvent>(ChatEvent.Cancelled)

        assertTrue(encoded.contains("\"type\":\"cancelled\""))
        assertEquals(ChatEvent.Cancelled, json.decodeFromString<ChatEvent>(encoded))
    }

    @Test
    fun agentActionsAndProposalsRoundTripWithoutExecutor() {
        val proposals = listOf(
            AgentActionProposal(AgentAction.CreateReminder(
                title = "Call back",
                time = "2026-09-21T10:00:00+08:00",
            )),
            AgentActionProposal(AgentAction.OpenApp(
                packageName = "com.example.calendar",
                displayName = "Calendar",
            )),
        )

        proposals.map { it.action }.forEach { action ->
            assertEquals(action, json.decodeFromString<AgentAction>(json.encodeToString(action)))
        }
        proposals.forEach { proposal ->
            assertEquals(
                proposal,
                json.decodeFromString<AgentActionProposal>(json.encodeToString(proposal)),
            )
        }
    }
}
