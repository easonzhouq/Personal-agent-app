package com.example.agentchat.data.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionRequestStateTest {
    private val state = PermissionRequestState()

    @Test
    fun grantedResultConsumesPendingActionOnlyOnce() {
        state.begin(PendingPermissionAction.SendMessage)

        assertEquals(PendingPermissionAction.SendMessage, state.finish(granted = true))
        assertNull(state.finish(granted = true))
    }

    @Test
    fun deniedResultClearsPendingAction() {
        state.begin(PendingPermissionAction.StartVoice)

        assertNull(state.finish(granted = false))
        assertNull(state.finish(granted = true))
    }
}
