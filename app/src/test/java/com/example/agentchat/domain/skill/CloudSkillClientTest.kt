package com.example.agentchat.domain.skill

import com.example.agentchat.data.skill.DisabledCloudSkillClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSkillClientTest {
    @Test
    fun disabledClientReturnsExplicitFailureWithoutNetwork() = runTest {
        val events = DisabledCloudSkillClient().run("notes", "整理笔记", "conversation").toList()

        assertEquals(CloudSkillEvent.Started, events.first())
        val failure = events[1] as CloudSkillEvent.Failed
        assertEquals("cloud_disabled", failure.code)
        assertTrue(events.last() is CloudSkillEvent.Completed)
    }
}
