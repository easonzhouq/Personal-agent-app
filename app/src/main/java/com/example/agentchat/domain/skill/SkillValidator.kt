package com.example.agentchat.domain.skill

data class SkillValidationResult(
    val valid: Boolean,
    val unknownToolNames: List<String> = emptyList(),
)

object SkillValidator {
    fun validate(skill: Skill, availableToolNames: Set<String>): SkillValidationResult {
        val unknown = skill.toolNames.filterNot { it in availableToolNames }.distinct()
        return SkillValidationResult(valid = unknown.isEmpty(), unknownToolNames = unknown)
    }
}
