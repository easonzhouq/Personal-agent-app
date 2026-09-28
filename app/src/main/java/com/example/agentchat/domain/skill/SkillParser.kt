package com.example.agentchat.domain.skill

class SkillParseException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

object SkillParser {
    const val MAX_INSTRUCTION_CHARS = 100_000

    fun parse(markdown: String): Result<Skill> = runCatching {
        val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val firstLineEnd = normalized.indexOf('\n')
        if (!normalized.startsWith("---") || firstLineEnd < 0) {
            throw SkillParseException("missing_front_matter", "Skill 文件必须以 --- 开头")
        }
        val closingMarker = normalized.indexOf("\n---", firstLineEnd)
        if (closingMarker < 0) {
            throw SkillParseException("malformed_front_matter", "Skill 文件缺少 front matter 结束标记")
        }
        val metadata = parseMetadata(normalized.substring(firstLineEnd + 1, closingMarker))
        val body = normalized.substring(closingMarker + "\n---".length).trim()
        if (body.isBlank()) throw SkillParseException("missing_body", "Skill 指令内容不能为空")
        if (body.length > MAX_INSTRUCTION_CHARS) {
            throw SkillParseException("body_too_large", "Skill 指令内容不能超过 $MAX_INSTRUCTION_CHARS 个字符")
        }

        val id = metadata.required("id")
        if (!ID_PATTERN.matches(id)) throw SkillParseException("invalid_id", "Skill id 格式无效")
        val name = metadata.required("name")
        val description = metadata.required("description")
        val requiresConfirmation = metadata["requires_confirmation"]?.let { value ->
            value.toBooleanStrictOrNull()
                ?: throw SkillParseException("invalid_confirmation", "requires_confirmation 必须是 true 或 false")
        } ?: false
        Skill(
            id = id,
            name = name,
            description = description,
            version = metadata["version"]?.takeIf { it.isNotBlank() } ?: "1.0.0",
            triggers = metadata.list("triggers"),
            toolNames = metadata.list("tools"),
            instructions = body,
            requiresConfirmation = requiresConfirmation,
        )
    }

    private fun parseMetadata(value: String): Map<String, String> = buildMap {
        value.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith('#')) return@forEach
            val separator = trimmed.indexOf(':')
            if (separator <= 0) throw SkillParseException("malformed_metadata", "Skill 元数据格式无效：$trimmed")
            val key = trimmed.substring(0, separator).trim().lowercase()
            val parsedValue = trimmed.substring(separator + 1).trim().trim('"', '\'')
            put(key, parsedValue)
        }
    }

    private fun Map<String, String>.required(key: String): String = this[key]
        ?.takeIf { it.isNotBlank() }
        ?: throw SkillParseException("missing_$key", "Skill 缺少 $key")

    private fun Map<String, String>.list(key: String): List<String> = this[key]
        .orEmpty()
        .split(',', '，')
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
}
