package com.example.agentchat.ui.skill

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agentchat.domain.skill.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkillScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun emptyStateProvidesImportAction() {
        var imported = false
        compose.setContent {
            SkillScreenContent(
                state = SkillUiState(),
                onImportClick = { imported = true },
                onToggle = { _, _ -> },
                onDelete = {},
                onBack = {},
                onManualSelect = {},
            )
        }

        compose.onNodeWithText("还没有 Skill").assertIsDisplayed()
        compose.onNodeWithText("导入 Skill").performClick()
        assertTrue(imported)
    }

    @Test
    fun listsSkillAndHandlesToggleDelete() {
        var toggled: Pair<String, Boolean>? = null
        var deleted: String? = null
        compose.setContent {
            SkillScreenContent(
                state = SkillUiState(
                    skills = listOf(
                        Skill("notes", "笔记助手", "整理笔记", instructions = "保持结构清晰。"),
                    ),
                ),
                onImportClick = {},
                onToggle = { id, enabled -> toggled = id to enabled },
                onDelete = { deleted = it },
                onBack = {},
                onManualSelect = {},
            )
        }

        compose.onNodeWithText("笔记助手").assertIsDisplayed()
        compose.onNodeWithText("整理笔记").assertIsDisplayed()
        compose.onNodeWithContentDescription("停用 Skill 笔记助手").performClick()
        compose.onNodeWithContentDescription("删除 Skill 笔记助手").performClick()
        assertEquals("notes" to false, toggled)
        assertEquals("notes", deleted)
    }
}
