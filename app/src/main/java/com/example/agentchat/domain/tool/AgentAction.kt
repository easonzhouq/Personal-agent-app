package com.example.agentchat.domain.tool

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface AgentAction {
    @Serializable
    @SerialName("create_reminder")
    data class CreateReminder(
        val title: String,
        val time: String,
    ) : AgentAction

    @Serializable
    @SerialName("open_app")
    data class OpenApp(
        val packageName: String,
        val displayName: String,
    ) : AgentAction
}

@Serializable
/** A model-generated action proposal; it is data only and must not be executed directly. */
data class AgentActionProposal(
    val action: AgentAction,
)
