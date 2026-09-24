package com.example.agentchat.data.calendar

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarDraftParserTest {
    private val now = ZonedDateTime.of(2026, 9, 24, 9, 0, 0, 0, ZoneId.of("Asia/Shanghai"))

    @Test
    fun parsesTomorrowMorningEventAndDefaultsOneHourDuration() {
        val draft = CalendarDraftParser.parse("明天上午10点安排产品评审", now)

        requireNotNull(draft)
        assertEquals("产品评审", draft.title)
        assertEquals(now.plusDays(1).withHour(10).withMinute(0), draft.startAt)
        assertEquals(draft.startAt.plusHours(1), draft.endAt)
    }

    @Test
    fun ignoresReminderWithoutRecognizableTime() {
        assertNull(CalendarDraftParser.parse("提醒我准备产品评审", now))
    }
}
