package com.example.agentchat.domain.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillParserTest {
    @Test
    fun parsesFrontMatterAndInstructionBody() {
        val result = SkillParser.parse(
            """
            ---
            id: travel-planner
            name: 旅行规划
            description: 规划旅行
            version: 1.0.0
            triggers: 旅行, 行程
            tools: web_search, calendar_context
            requires_confirmation: false
            ---
            先确认日期，再生成计划。
            """.trimIndent(),
        )

        val skill = result.getOrThrow()
        assertEquals("travel-planner", skill.id)
        assertEquals(listOf("旅行", "行程"), skill.triggers)
        assertEquals(listOf("web_search", "calendar_context"), skill.toolNames)
        assertEquals("先确认日期，再生成计划。", skill.instructions)
    }

    @Test
    fun omittedOptionalFieldsUseSafeDefaults() {
        val skill = SkillParser.parse(
            """
            ---
            id: notes
            name: 笔记
            description: 整理笔记
            ---
            请整理内容。
            """.trimIndent(),
        ).getOrThrow()

        assertEquals("1.0.0", skill.version)
        assertTrue(skill.triggers.isEmpty())
        assertTrue(skill.toolNames.isEmpty())
        assertTrue(!skill.requiresConfirmation)
    }

    @Test
    fun rejectsMissingRequiredFieldsAndBody() {
        val missingName = SkillParser.parse("""
            ---
            id: broken
            description: no name
            ---
            instructions
        """.trimIndent())
        val missingBody = SkillParser.parse("""
            ---
            id: empty
            name: Empty
            description: Empty body
            ---
        """.trimIndent())

        assertTrue(missingName.isFailure)
        assertTrue(missingBody.isFailure)
    }

    @Test
    fun rejectsOversizedInstructionBody() {
        val body = "x".repeat(SkillParser.MAX_INSTRUCTION_CHARS + 1)
        val result = SkillParser.parse(
            """
            ---
            id: huge
            name: Huge
            description: Huge skill
            ---
            $body
            """.trimIndent(),
        )

        assertTrue(result.isFailure)
    }
}
