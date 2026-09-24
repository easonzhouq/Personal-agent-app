package com.example.agentchat.data.calendar

import java.time.ZonedDateTime

data class CalendarEventDraft(
    val title: String,
    val startAt: ZonedDateTime,
    val endAt: ZonedDateTime,
    val location: String? = null,
    val description: String? = null,
)

data class CalendarEventSummary(
    val title: String,
    val startAt: ZonedDateTime,
    val endAt: ZonedDateTime,
    val location: String? = null,
)
