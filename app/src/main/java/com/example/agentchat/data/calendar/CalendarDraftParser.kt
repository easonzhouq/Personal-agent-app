package com.example.agentchat.data.calendar

import java.time.ZonedDateTime

object CalendarDraftParser {
    private val timePattern = Regex("(上午|下午|晚上|早上|中午)?\\s*(\\d{1,2})(?:点|:(\\d{2}))(?:(半)|(\\d{1,2})分)?")

    fun parse(text: String, now: ZonedDateTime = ZonedDateTime.now()): CalendarEventDraft? {
        if (!text.containsAny("日程", "提醒", "安排", "会议", "约") ) return null
        val timeMatch = timePattern.find(text) ?: return null
        val rawHour = timeMatch.groupValues[2].toIntOrNull() ?: return null
        if (rawHour !in 0..23) return null
        val minute = when {
            timeMatch.groupValues[4].isNotEmpty() -> 30
            timeMatch.groupValues[5].isNotEmpty() -> timeMatch.groupValues[5].toIntOrNull() ?: return null
            else -> timeMatch.groupValues[3].toIntOrNull() ?: 0
        }
        if (minute !in 0..59) return null
        val marker = timeMatch.groupValues[1]
        val hour = when {
            marker in setOf("下午", "晚上", "中午") && rawHour in 1..11 -> rawHour + 12
            else -> rawHour
        }
        val dayOffset = when {
            text.contains("后天") -> 2L
            text.contains("明天") -> 1L
            else -> 0L
        }
        var start = now.plusDays(dayOffset).withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (dayOffset == 0L && start.isBefore(now)) start = start.plusDays(1)
        val title = text
            .replace(Regex("今天|明天|后天"), " ")
            .replace(timeMatch.value, " ")
            .replace(Regex("提醒我|帮我|创建日程|创建|安排|设置"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '，', ',', '。', '.')
            .ifBlank { "未命名日程" }
        return CalendarEventDraft(title = title, startAt = start, endAt = start.plusHours(1))
    }

    private fun String.containsAny(vararg values: String) = values.any { contains(it) }
}
