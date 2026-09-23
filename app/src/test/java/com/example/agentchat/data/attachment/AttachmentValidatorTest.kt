package com.example.agentchat.data.attachment

import com.example.agentchat.domain.model.Attachment
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.model.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentValidatorTest {
    private val visionConfig = ModelConfig("id", "model", "", "", ProviderProtocol.CUSTOM, supportsVision = true, supportsFiles = true)

    @Test fun acceptsFiveSupportedAttachmentsWithinLimit() {
        val attachments = (0 until 5).map { attachment("content://$it", "text/plain", 10) }
        assertTrue(AttachmentValidator.validate(attachments, visionConfig).isValid)
    }

    @Test fun rejectsEmptyUriWithStableReasonCode() {
        assertEquals(AttachmentValidationReason.EMPTY_URI, AttachmentValidator.validate(listOf(attachment("", "text/plain", 1)), visionConfig).reason)
    }

    @Test fun rejectsUnknownMimeAndUnreadableSize() {
        assertEquals(AttachmentValidationReason.UNKNOWN_MIME, AttachmentValidator.validate(listOf(attachment("content://x", "", 1)), visionConfig).reason)
        assertEquals(AttachmentValidationReason.SIZE_UNAVAILABLE, AttachmentValidator.validate(listOf(attachment("content://x", "text/plain", -1)), visionConfig).reason)
    }

    @Test fun rejectsDuplicateUriAndTooManyAndTooLarge() {
        assertEquals(AttachmentValidationReason.DUPLICATE_URI, AttachmentValidator.validate(listOf(attachment("content://x", "text/plain", 1), attachment("content://x", "text/plain", 1)), visionConfig).reason)
        assertEquals(AttachmentValidationReason.TOO_MANY, AttachmentValidator.validate((0..5).map { attachment("content://$it", "text/plain", 1) }, visionConfig).reason)
        assertEquals(AttachmentValidationReason.TOO_LARGE, AttachmentValidator.validate(listOf(attachment("content://x", "text/plain", 10 * 1024 * 1024 + 1)), visionConfig).reason)
    }

    @Test fun rejectsUnsupportedTypeForModel() {
        val config = visionConfig.copy(supportsFiles = false)
        assertEquals(AttachmentValidationReason.MODEL_UNSUPPORTED, AttachmentValidator.validate(listOf(attachment("content://x", "text/plain", 1)), config).reason)
    }

    private fun attachment(uri: String, mime: String, size: Long) = Attachment(uri, "file", mime, size, uri)
}
