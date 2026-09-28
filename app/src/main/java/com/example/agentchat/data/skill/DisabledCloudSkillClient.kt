package com.example.agentchat.data.skill

import com.example.agentchat.domain.skill.CloudSkillClient
import com.example.agentchat.domain.skill.CloudSkillEvent
import com.example.agentchat.domain.skill.Skill
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DisabledCloudSkillClient : CloudSkillClient {
    override suspend fun listSkills(): List<Skill> = emptyList()

    override suspend fun match(query: String, conversationId: String): List<Skill> = emptyList()

    override fun run(skillId: String, query: String, conversationId: String): Flow<CloudSkillEvent> = flow {
        emit(CloudSkillEvent.Started)
        emit(CloudSkillEvent.Failed("cloud_disabled", "云端 Skill 尚未配置"))
        emit(CloudSkillEvent.Completed)
    }
}
