package com.example.agentchat.data.skill

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.agentchat.domain.skill.Skill
import com.example.agentchat.domain.skill.SkillSource

@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val version: String,
    val triggers: String,
    val toolNames: String,
    val instructions: String,
    val enabled: Boolean,
    val requiresConfirmation: Boolean,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
)

fun SkillEntity.toDomain() = Skill(
    id = id,
    name = name,
    description = description,
    version = version,
    triggers = splitValues(triggers),
    toolNames = splitValues(toolNames),
    instructions = instructions,
    enabled = enabled,
    requiresConfirmation = requiresConfirmation,
    source = runCatching { SkillSource.valueOf(source) }.getOrDefault(SkillSource.LOCAL),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Skill.toEntity(updatedAt: Long = System.currentTimeMillis()) = SkillEntity(
    id = id,
    name = name,
    description = description,
    version = version,
    triggers = triggers.joinToString(","),
    toolNames = toolNames.joinToString(","),
    instructions = instructions,
    enabled = enabled,
    requiresConfirmation = requiresConfirmation,
    source = source.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun splitValues(value: String) = value.split(',').map(String::trim).filter(String::isNotBlank)
