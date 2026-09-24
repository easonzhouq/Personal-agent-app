package com.example.agentchat.data.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeChunkerTest {
    @Test
    fun chunksKeepSourceMetadataAndRespectMaximumLength() {
        val chunks = KnowledgeChunker.chunk(
            sourceId = "source-1",
            sourceName = "guide.md",
            content = "第一段内容。\n\n第二段内容很长，用于测试切片边界和来源信息。",
            maxCharacters = 12,
        )

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.text.length <= 12 })
        assertTrue(chunks.all { it.sourceId == "source-1" && it.sourceName == "guide.md" })
        assertEquals(chunks.indices.toList(), chunks.map { it.chunkIndex })
    }

    @Test
    fun ranksRelevantChunkBeforeUnrelatedChunk() {
        val chunks = listOf(
            KnowledgeChunk("weather", "source-1", "notes.txt", 0, "天气查询使用 Open-Meteo 接口。"),
            KnowledgeChunk("database", "source-1", "notes.txt", 1, "数据库迁移需要增加版本号。"),
        )

        val ranked = KnowledgeChunker.rank("如何查询天气", chunks)

        assertEquals("天气查询使用 Open-Meteo 接口。", ranked.first().text)
        assertEquals(1, ranked.size)
        assertTrue(ranked.first().score > 0)
    }
}
