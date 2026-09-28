package com.example.agentchat.data.skill

import com.example.agentchat.data.db.AgentDatabase
import com.example.agentchat.domain.skill.Skill
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SkillRepository(private val database: AgentDatabase) {
    private val dao = database.skillDao()

    fun observeSkills(): Flow<List<Skill>> = dao.observeAll().map { list -> list.map(SkillEntity::toDomain) }

    fun observeEnabledSkills(): Flow<List<Skill>> = dao.observeEnabled().map { list -> list.map(SkillEntity::toDomain) }

    suspend fun getSkill(id: String): Skill? = dao.findById(id)?.toDomain()

    suspend fun save(skill: Skill) {
        val existing = dao.findById(skill.id)
        val now = System.currentTimeMillis()
        dao.insert(skill.copy(createdAt = existing?.createdAt ?: skill.createdAt, updatedAt = now).toEntity(now))
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setEnabled(id, enabled, System.currentTimeMillis())
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun deleteAll() = dao.deleteAll()
}
