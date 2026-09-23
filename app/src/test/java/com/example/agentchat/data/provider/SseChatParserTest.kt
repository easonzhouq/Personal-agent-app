package com.example.agentchat.data.provider

import com.example.agentchat.domain.model.ChatEvent
import com.example.agentchat.domain.model.ChatError
import com.example.agentchat.domain.model.Usage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseChatParserTest {
    @Test
    fun cachesUsageAndCompletesExactlyOnceAtDoneWhileIgnoringKeepAliveLines() = runTest {
        val source = Buffer().writeUtf8(
            "\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n" +
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}\n" +
                "data: [DONE]\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"ignored\"}}]}\n",
        )

        assertEquals(
            listOf(
                ChatEvent.Delta("Hel"),
                ChatEvent.Delta("lo"),
                ChatEvent.Completed(Usage(2, 3, 5)),
            ),
            SseChatParser().parse(source).toList(),
        )
    }

    @Test
    fun malformedJsonIsTerminalAndCannotCompleteOrEmitLaterDelta() = runTest {
        val source = Buffer().writeUtf8(
            "data: {not-json}\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"ignored\"}}]}\n" +
                "data: [DONE]\n",
        )

        val events = SseChatParser().parse(source).toList()
        assertEquals(listOf(ChatEvent.Failed(ChatError("malformed_json", "Malformed provider event"))), events)
        assertTrue(events.none { it is ChatEvent.Completed || it is ChatEvent.Delta })
    }

    @Test
    fun missingDoneFailsWithIncompleteStream() = runTest {
        val source = Buffer().writeUtf8(
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}\n",
        )

        assertEquals(listOf(ChatEvent.Failed(ChatError("incomplete_stream", "Provider stream ended before [DONE]"))), SseChatParser().parse(source).toList())
    }

    @Test
    fun ignoresNullContentAndRoleOnlyDeltasUntilDone() = runTest {
        val source = Buffer().writeUtf8(
            "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null}}]}\n" +
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n" +
                "data: [DONE]\n",
        )

        assertEquals(listOf(ChatEvent.Completed()), SseChatParser().parse(source).toList())
    }
}
