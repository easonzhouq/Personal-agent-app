package com.example.agentchat.data.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agentchat.data.db.AgentDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KnowledgeRepositoryTest {
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
    fun importRetrieveAndDeleteSource() = runBlocking {
        val repository = KnowledgeRepository(database)

        repository.importDocument(
            sourceId = "source-1",
            displayName = "guide.md",
            contentUri = "content://guide",
            content = "天气查询使用 Open-Meteo 接口。\n\n数据库迁移需要增加版本号。",
        )

        assertEquals(listOf("guide.md"), repository.observeSources().first().map { it.displayName })
        val matches = repository.retrieveRelevant("如何查询天气")
        assertEquals(1, matches.size)
        assertTrue(matches.single().text.contains("Open-Meteo"))

        repository.deleteSource("source-1")

        assertTrue(repository.observeSources().first().isEmpty())
        assertTrue(repository.retrieveRelevant("天气").isEmpty())
    }
}
