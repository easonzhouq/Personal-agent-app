package com.example.agentchat.domain.skill

data class SkillMatch(
    val skill: Skill,
    val score: Int,
)

object SkillMatcher {
    private const val MAX_RESULTS = 3

    fun match(
        query: String,
        skills: List<Skill>,
        availableToolNames: Set<String>,
    ): List<SkillMatch> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) return emptyList()
        return skills.asSequence()
            .filter { it.enabled }
            .filter { SkillValidator.validate(it, availableToolNames).valid }
            .mapNotNull { skill ->
                val score = score(normalizedQuery, skill)
                if (score > 0) SkillMatch(skill, score) else null
            }
            .sortedWith(compareByDescending<SkillMatch> { it.score }.thenBy { it.skill.name })
            .take(MAX_RESULTS)
            .toList()
    }

    private fun score(query: String, skill: Skill): Int {
        val triggerScore = skill.triggers.count { trigger ->
            trigger.isNotBlank() && query.contains(trigger.lowercase())
        } * 100
        val nameScore = if (query.contains(skill.name.lowercase())) 50 else 0
        val descriptionScore = skill.description
            .split(Regex("[\\s,，。；;、]+"))
            .filter { it.length >= 2 }
            .count { term -> query.contains(term.lowercase()) } * 10
        return triggerScore + nameScore + descriptionScore
    }
}
