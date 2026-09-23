package com.example.agentchat.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Attachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentUri: String,
)
