package com.example.agentchat.domain.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillMatcherTest {
    private val triggerSkill = Skill(
        id = "travel-planner",
        name = "旅行规划",
        description = "生成旅行安排",
        version = "1.0.0",
        triggers = listOf("旅行", "行程"),
        toolNames = listOf("web_search"),
        instructions = "先确认日期。",
    )
    private val descriptionSkill = Skill(
        id = "writer",
        name = "写作助手",
        description = "帮助整理和生成计划文本",
        version = "1.0.0",
        triggers = emptyList(),
        toolNames = emptyList(),
        instructions = "保持结构清晰。",
    )

    @Test
    fun triggerMatchRanksAboveDescriptionMatch() {
        val result = SkillMatcher.match(
            query = "帮我安排旅行行程",
            skills = listOf(descriptionSkill, triggerSkill),
            availableToolNames = setOf("web_search"),
        )

        assertEquals("travel-planner", result.first().skill.id)
    }

    @Test
    fun disabledAndUnknownToolSkillsAreExcluded() {
        val disabled = triggerSkill.copy(id = "disabled", enabled = false)
        val unknownTool = triggerSkill.copy(id = "unknown", toolNames = listOf("shell"))

        val result = SkillMatcher.match(
            query = "旅行规划",
            skills = listOf(disabled, unknownTool, triggerSkill),
            availableToolNames = setOf("web_search"),
        )

        assertEquals(listOf("travel-planner"), result.map { it.skill.id })
        assertTrue(result.first().score > 0)
    }

    @Test
    fun returnsAtMostThreeCandidates() {
        val skills = (1..5).map { index ->
            triggerSkill.copy(id = "travel-$index", triggers = listOf("旅行"))
        }

        val result = SkillMatcher.match("旅行", skills, setOf("web_search"))

        assertEquals(3, result.size)
    }
}
