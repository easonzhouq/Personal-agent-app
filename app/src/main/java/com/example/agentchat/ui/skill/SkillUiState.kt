package com.example.agentchat.ui.skill

import com.example.agentchat.domain.skill.Skill

data class SkillUiState(
    val skills: List<Skill> = emptyList(),
    val isImporting: Boolean = false,
    val error: String? = null,
)
