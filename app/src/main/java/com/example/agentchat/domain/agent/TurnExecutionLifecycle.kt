package com.example.agentchat.domain.agent

/** Lifecycle boundary used to keep a long-running agent turn alive in background. */
interface TurnExecutionLifecycle {
    fun onTurnStarted(conversationId: String)
    fun onTurnFinished(conversationId: String?, completed: Boolean)
}
