package com.example.agentchat.data.rag

import java.util.Locale

object KnowledgeChunker {
    fun chunk(
        sourceId: String,
        sourceName: String,
        content: String,
        maxCharacters: Int = 900,
    ): List<KnowledgeChunk> {
        require(maxCharacters > 0) { "maxCharacters must be positive" }
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()
        if (normalized.isBlank()) return emptyList()

        val paragraphs = normalized.split(Regex("\\n{2,}"))
            .map(String::trim)
            .filter(String::isNotBlank)
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) pieces += current.toString().trim()
            current.clear()
        }
        paragraphs.forEach { paragraph ->
            if (paragraph.length <= maxCharacters && current.length + paragraph.length + 1 <= maxCharacters) {
                if (current.isNotEmpty()) current.append('\n')
                current.append(paragraph)
            } else {
                flush()
                paragraph.chunked(maxCharacters).forEach { pieces += it.trim() }
            }
        }
        flush()
        return pieces.mapIndexed { index, text ->
            KnowledgeChunk(
                id = "$sourceId-$index",
                sourceId = sourceId,
                sourceName = sourceName,
                chunkIndex = index,
                text = text,
            )
        }
    }

    fun rank(query: String, chunks: List<KnowledgeChunk>, limit: Int = 6): List<KnowledgeChunk> {
        val terms = terms(query)
        if (terms.isEmpty()) return emptyList()
        return chunks.map { chunk ->
            val haystack = chunk.text.lowercase(Locale.ROOT)
            val score = terms.fold(0) { total, term ->
                when {
                    haystack.contains(term) && term.length > 2 -> total + 2
                    haystack.contains(term) -> total + 1
                    else -> total
                }
            }
            chunk.copy(score = score)
        }.filter { it.score > 0 }
            .sortedWith(compareByDescending<KnowledgeChunk> { it.score }.thenBy { it.chunkIndex })
            .take(limit)
    }

    private fun terms(value: String): Set<String> {
        val lower = value.lowercase(Locale.ROOT)
        val cjk = lower.filter { it in '\u4e00'..'\u9fff' }
        val cjkTerms = cjk.windowed(size = 2, step = 1, partialWindows = false).toSet()
        val latinTerms = Regex("[a-z0-9]{2,}").findAll(lower).map { it.value }.toSet()
        return cjkTerms + latinTerms
    }
}
