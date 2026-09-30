package com.example.agentchat.data.permission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRequirementTest {
    @Test
    fun cityWeatherDoesNotNeedDeviceLocation() {
        assertFalse(PermissionRequirement.needsCurrentLocation("北京今天天气怎么样"))
    }

    @Test
    fun currentWeatherNeedsDeviceLocation() {
        assertTrue(PermissionRequirement.needsCurrentLocation("查一下当前位置天气"))
    }

    @Test
    fun calendarQuestionNeedsReadPermission() {
        assertTrue(PermissionRequirement.needsCalendarRead("我今天有什么会议"))
    }

    @Test
    fun ordinaryChatNeedsNoDevicePermission() {
        assertFalse(PermissionRequirement.needsCurrentLocation("帮我润色这段话"))
        assertFalse(PermissionRequirement.needsCalendarRead("解释一下 RAG"))
    }
}
