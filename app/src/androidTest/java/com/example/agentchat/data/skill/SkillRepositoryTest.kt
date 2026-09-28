package com.example.agentchat.data.skill

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agentchat.data.db.AgentDatabase
import com.example.agentchat.domain.skill.Skill
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SkillRepositoryTest {
    private lateinit var database: AgentDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AgentDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun savesObservesUpdatesAndDeletesSkill() = runBlocking {
        val repository = SkillRepository(database)
        val skill = Skill(
            id = "notes",
            name = "笔记助手",
            description = "整理笔记",
            instructions = "保持结构清晰。",
        )

        repository.save(skill)
        assertEquals(listOf("notes"), repository.observeSkills().first().map { it.id })

        repository.setEnabled("notes", false)
        assertFalse(repository.observeSkills().first().single().enabled)

        repository.delete("notes")
        assertTrue(repository.observeSkills().first().isEmpty())
    }

    @Test
    fun savingSameIdReplacesInstruction() = runBlocking {
        val repository = SkillRepository(database)
        repository.save(Skill("notes", "笔记", "旧", instructions = "旧指令"))
        repository.save(Skill("notes", "笔记", "新", instructions = "新指令"))

        val stored = repository.getSkill("notes")
        assertEquals("新指令", stored?.instructions)
        assertEquals(1, repository.observeSkills().first().size)
    }
}
