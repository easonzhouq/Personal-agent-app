package com.example.agentchat.domain.tool

import kotlinx.serialization.json.JsonObject

/** Phase 2/3 handoff contract only. Phase 1 validates data but never invokes tools. */
interface AgentTool {
    val name: String
    suspend fun execute(input: JsonObject): ToolResult
}

enum class SourceKind {
    MODEL,
    USER,
    SYSTEM,
}

data class Source(val kind: SourceKind, val reference: String? = null)

sealed interface ToolResult {
    data class Success(val content: String, val sources: List<Source> = emptyList()) : ToolResult
    data class Failure(val code: String, val message: String, val sources: List<Source> = emptyList()) : ToolResult
}

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val reasons: List<String>) : ValidationResult
}

/** Marker for a future confirmation module; this Phase exposes no public constructor. */
sealed interface ConfirmedAgentAction

/** Future Phase 2/3 executor boundary. No Phase 1 implementation is provided or called. */
interface ActionExecutor {
    suspend fun validate(proposal: AgentActionProposal): ValidationResult
    suspend fun executeConfirmed(action: ConfirmedAgentAction): ActionResult
}

sealed interface ActionResult {
    data class Succeeded(val sources: List<Source> = emptyList()) : ActionResult
    data class Rejected(val reasons: List<String>, val sources: List<Source> = emptyList()) : ActionResult
    data class Failed(val code: String, val message: String, val sources: List<Source> = emptyList()) : ActionResult
}
