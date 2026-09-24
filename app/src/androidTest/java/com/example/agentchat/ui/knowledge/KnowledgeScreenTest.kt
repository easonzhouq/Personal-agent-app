package com.example.agentchat.ui.knowledge

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agentchat.data.rag.KnowledgeSource
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KnowledgeScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun emptyStateProvidesImportAction() {
        var imported = false
        compose.setContent {
            KnowledgeScreenContent(
                state = KnowledgeUiState(),
                onImportClick = { imported = true },
                onDelete = {},
                onBack = {},
            )
        }

        compose.onNodeWithText("还没有知识文档").assertIsDisplayed()
        compose.onNodeWithText("导入知识文件").performClick()
        assertTrue(imported)
    }

    @Test
    fun listsSourceAndDeletesIt() {
        var deleted: String? = null
        compose.setContent {
            KnowledgeScreenContent(
                state = KnowledgeUiState(
                    sources = listOf(KnowledgeSource("source-1", "guide.md", "content://guide", 3, 1L)),
                ),
                onImportClick = {},
                onDelete = { deleted = it },
                onBack = {},
            )
        }

        compose.onNodeWithText("guide.md").assertIsDisplayed()
        compose.onNodeWithText("3 个片段").assertIsDisplayed()
        compose.onNodeWithContentDescription("删除知识文档 guide.md").performClick()
        assertTrue(deleted == "source-1")
    }
}
