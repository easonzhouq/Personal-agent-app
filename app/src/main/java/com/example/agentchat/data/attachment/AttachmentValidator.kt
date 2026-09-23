package com.example.agentchat.data.attachment

import com.example.agentchat.domain.model.Attachment
import com.example.agentchat.domain.model.ModelConfig

enum class AttachmentValidationReason {
    EMPTY_URI, UNKNOWN_MIME, DUPLICATE_URI, SIZE_UNAVAILABLE, TOO_LARGE, TOO_MANY, MODEL_UNSUPPORTED,
}

data class AttachmentValidationResult(val reason: AttachmentValidationReason? = null) {
    val isValid: Boolean get() = reason == null
}

object AttachmentValidator {
    const val MAX_SIZE_BYTES = 10L * 1024 * 1024
    const val MAX_ATTACHMENTS = 5
    private val fileMimeTypes = setOf("text/plain", "text/markdown", "application/json")

    fun validate(attachments: List<Attachment>, config: ModelConfig): AttachmentValidationResult {
        if (attachments.size > MAX_ATTACHMENTS) return invalid(AttachmentValidationReason.TOO_MANY)
        val seen = HashSet<String>()
        for (attachment in attachments) {
            if (attachment.contentUri.isBlank()) return invalid(AttachmentValidationReason.EMPTY_URI)
            if (!seen.add(attachment.contentUri)) return invalid(AttachmentValidationReason.DUPLICATE_URI)
            val mime = attachment.mimeType.lowercase()
            val isImage = mime.startsWith("image/")
            val isFile = mime in fileMimeTypes
            if (!isImage && !isFile) return invalid(AttachmentValidationReason.UNKNOWN_MIME)
            if (attachment.sizeBytes < 0) return invalid(AttachmentValidationReason.SIZE_UNAVAILABLE)
            if (attachment.sizeBytes > MAX_SIZE_BYTES) return invalid(AttachmentValidationReason.TOO_LARGE)
            if ((isImage && !config.supportsVision) || (isFile && !config.supportsFiles)) {
                return invalid(AttachmentValidationReason.MODEL_UNSUPPORTED)
            }
        }
        return AttachmentValidationResult()
    }

    private fun invalid(reason: AttachmentValidationReason) = AttachmentValidationResult(reason)
}
