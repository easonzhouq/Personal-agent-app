package com.example.agentchat.domain.skill

import com.example.agentchat.data.calendar.CalendarEventSummary
import com.example.agentchat.data.rag.KnowledgeChunk
import com.example.agentchat.data.search.WebSearchResult
import com.example.agentchat.domain.agent.AgentRuntime
import com.example.agentchat.domain.model.ChatMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRuntimeTest {
    @Test
    fun selectedSkillIsIsolatedAndIncludedInRuntimeContext() = runTest {
        val runtime = AgentRuntime(
            webSearch = { WebSearchResult() },
            knowledgeRetriever = { emptyList<KnowledgeChunk>() },
            historyRetriever = { _, _ -> emptyList<ChatMessage>() },
            calendarRetriever = { emptyList<CalendarEventSummary>() },
        )
        val skill = Skill(
            id = "travel-planner",
            name = "旅行规划",
            description = "规划行程",
            instructions = "先确认出发日期，再生成计划。",
            toolNames = emptyList(),
        ).toExecutionContext()

        val context = runtime.enrich("规划旅行", "conversation", skill)

        assertTrue(context.prompt.contains("旅行规划"))
        assertTrue(context.prompt.contains("先确认出发日期"))
        assertTrue(context.prompt.contains("不受信任"))
    }
}
