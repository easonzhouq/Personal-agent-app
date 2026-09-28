package com.example.agentchat.domain.skill

enum class SkillSource {
    LOCAL,
    CLOUD,
}

data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val version: String = "1.0.0",
    val triggers: List<String> = emptyList(),
    val toolNames: List<String> = emptyList(),
    val instructions: String,
    val enabled: Boolean = true,
    val requiresConfirmation: Boolean = false,
    val source: SkillSource = SkillSource.LOCAL,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)
