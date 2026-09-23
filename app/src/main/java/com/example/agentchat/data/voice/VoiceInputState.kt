package com.example.agentchat.data.voice

enum class VoiceInputState {
    IDLE,
    LISTENING,
    TRANSCRIBING,
    RESULT,
    ERROR,
    UNAVAILABLE,
}
