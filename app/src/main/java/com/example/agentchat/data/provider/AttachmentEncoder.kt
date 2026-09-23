package com.example.agentchat.data.provider

import com.example.agentchat.domain.model.Attachment
import kotlinx.serialization.json.JsonElement

/** Converts an attachment into the provider-specific content item. */
fun interface AttachmentEncoder {
    fun encode(attachment: Attachment): JsonElement
}
