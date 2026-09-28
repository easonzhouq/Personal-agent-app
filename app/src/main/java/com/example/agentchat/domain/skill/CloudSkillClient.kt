package com.example.agentchat.domain.skill

import com.example.agentchat.domain.agent.AgentContext
import kotlinx.coroutines.flow.Flow

sealed interface CloudSkillEvent {
    data object Started : CloudSkillEvent
    data class Context(val value: AgentContext) : CloudSkillEvent
    data class Failed(val code: String, val message: String) : CloudSkillEvent
    data object Completed : CloudSkillEvent
}

interface CloudSkillClient {
    suspend fun listSkills(): List<Skill>
    suspend fun match(query: String, conversationId: String): List<Skill>
    fun run(skillId: String, query: String, conversationId: String): Flow<CloudSkillEvent>
}
