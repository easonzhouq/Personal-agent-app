package com.example.agentchat.domain.skill

data class SkillExecutionContext(
    val id: String,
    val name: String,
    val version: String,
    val instructions: String,
    val validatedToolNames: List<String>,
    val requiresConfirmation: Boolean,
)

fun Skill.toExecutionContext(availableToolNames: Set<String> = emptySet()) = SkillExecutionContext(
    id = id,
    name = name,
    version = version,
    instructions = instructions,
    validatedToolNames = if (availableToolNames.isEmpty()) toolNames else toolNames.filter { it in availableToolNames },
    requiresConfirmation = requiresConfirmation,
)
