package com.example.agentchat.data.permission

sealed interface PendingPermissionAction {
    data object SendMessage : PendingPermissionAction
    data object StartVoice : PendingPermissionAction
}

/** Holds one UI action while Android's permission dialog is visible. */
class PermissionRequestState {
    private var pendingAction: PendingPermissionAction? = null

    fun begin(action: PendingPermissionAction) {
        pendingAction = action
    }

    fun finish(granted: Boolean): PendingPermissionAction? {
        val action = pendingAction
        pendingAction = null
        return action.takeIf { granted }
    }

    fun clear() {
        pendingAction = null
    }
}
