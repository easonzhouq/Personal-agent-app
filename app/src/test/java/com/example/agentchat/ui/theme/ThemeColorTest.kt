package com.example.agentchat.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeColorTest {
    @Test
    fun skyCreamUsesApprovedLightTokens() {
        assertEquals(Color(0xFFF7FBFF), SkyCreamBackground)
        assertEquals(Color(0xFF5A9BD6), SkyCreamPrimary)
        assertEquals(Color(0xFFD95C62), SkyCreamError)
    }

    @Test
    fun nightSkyUsesApprovedDarkTokens() {
        assertEquals(Color(0xFF101A28), NightSkyBackground)
        assertEquals(Color(0xFF8CC4F4), NightSkyPrimary)
        assertEquals(Color(0xFFFF9A9F), NightSkyError)
    }
}
