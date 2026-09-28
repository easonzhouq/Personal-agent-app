package com.example.agentchat.domain.agent

import com.example.agentchat.domain.tool.AgentTool
import com.example.agentchat.domain.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * The single dispatch point for tools exposed to the agent runtime.
 *
 * Keeping lookup and error handling here means callers do not need to know how
 * a tool is registered, and an unavailable tool cannot crash a chat request.
 */
class AgentToolRegistry(tools: List<AgentTool>) {
    private val toolsByName = tools.associateBy { it.name }

    val toolNames: Set<String> get() = toolsByName.keys

    suspend fun call(name: String, input: JsonObject): ToolResult {
        val tool = toolsByName[name]
            ?: return ToolResult.Failure(
                code = "unknown_tool",
                message = "工具不存在：$name",
            )
        return try {
            tool.execute(input)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ToolResult.Failure(
                code = "tool_failed",
                message = error.message ?: "工具执行失败",
            )
        }
    }
}
