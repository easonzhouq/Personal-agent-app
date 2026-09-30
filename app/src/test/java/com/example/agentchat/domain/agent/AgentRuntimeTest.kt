package com.example.agentchat.domain.agent

import com.example.agentchat.data.calendar.CalendarEventSummary
import com.example.agentchat.data.search.WebSearchContext
import com.example.agentchat.data.search.WebSearchResult
import com.example.agentchat.data.rag.KnowledgeChunk
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.MessageStatus
import com.example.agentchat.domain.model.Role
import com.example.agentchat.domain.tool.AgentTool
import com.example.agentchat.domain.tool.ToolResult
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeTest {
    @Test
    fun registryExecutesRegisteredToolAndRejectsUnknownTool() = runTest {
        val registry = AgentToolRegistry(
            listOf(object : AgentTool {
                override val name = "echo"
                override suspend fun execute(input: JsonObject) = ToolResult.Success("ok")
            }),
        )

        assertTrue(registry.call("echo", JsonObject(emptyMap())) is ToolResult.Success)
        val failure = registry.call("missing", JsonObject(emptyMap())) as ToolResult.Failure
        assertEquals("unknown_tool", failure.code)
    }

    @Test
    fun runtimeCombinesWebKnowledgeHistoryAndCalendarContexts() = runTest {
        val runtime = AgentRuntime(
            webSearch = { WebSearchResult(WebSearchContext("最新新闻", "联网结果", listOf("https://example.com"))) },
            knowledgeRetriever = { listOf(KnowledgeChunk("k", "s", "guide.md", 0, "本地知识")) },
            historyRetriever = { _, _ -> listOf(ChatMessage("h", "c", Role.USER, "历史记忆", status = MessageStatus.COMPLETED)) },
            calendarRetriever = { listOf(CalendarEventSummary("会议", ZonedDateTime.parse("2026-09-25T10:00:00+08:00"), ZonedDateTime.parse("2026-09-25T11:00:00+08:00"))) },
        )

        val context = runtime.enrich("最新新闻，我有什么日程", "conversation")

        assertTrue(context.prompt.contains("联网结果"))
        assertTrue(context.prompt.contains("本地知识"))
        assertTrue(context.prompt.contains("历史记忆"))
        assertTrue(context.prompt.contains("会议"))
        assertEquals(listOf("web_search", "knowledge_search", "history_search", "calendar_context"), context.toolNames)
    }

    @Test
    fun directLocationRequestUsesWebLocationTool() = runTest {
        val runtime = AgentRuntime(
            webSearch = { WebSearchResult(WebSearchContext("获取我的定位", "坐标", emptyList())) },
            knowledgeRetriever = { emptyList() },
            historyRetriever = { _, _ -> emptyList() },
            calendarRetriever = { emptyList() },
        )

        val context = runtime.enrich("获取我的定位", "conversation")

        assertTrue(context.toolNames.contains("web_search"))
        assertTrue(context.prompt.contains("坐标"))
    }
}
