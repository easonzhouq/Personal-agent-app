package com.example.agentchat.data.attachment

import com.example.agentchat.domain.model.Attachment
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentResolverAttachmentEncoderTest {
    @Test fun readsUriOnlyDuringEncodeAndProducesProtocolContent() {
        var reads = 0
        val encoder = ContentResolverAttachmentEncoder(streamOpener = { reads++; ByteArrayInputStream("hello".toByteArray()) })
        val attachment = Attachment("a", "notes.txt", "text/plain", 5, "content://private/document/42")
        assertTrue(reads == 0)
        val encoded = encoder.encode(attachment).toString()
        assertTrue(reads == 1)
        assertTrue(encoded.contains("\"type\":\"text\""))
        assertTrue(encoded.contains("hello"))
        assertTrue(!encoded.contains("private/document/42"))
    }

    @Test fun readFailureIsTypedAndDoesNotExposeUri() {
        val encoder = ContentResolverAttachmentEncoder(streamOpener = { throw IllegalStateException("secret path") })
        val attachment = Attachment("a", "notes.txt", "text/plain", 1, "content://private/secret")
        try {
            encoder.encode(attachment)
            throw AssertionError("expected AttachmentReadError")
        } catch (error: AttachmentReadError) {
            assertTrue(!error.message.orEmpty().contains("secret"))
            assertTrue(!error.message.orEmpty().contains("private"))
        }
    }

    @Test fun declaredOverLimitFailsBeforeOpeningUri() {
        var reads = 0
        val encoder = ContentResolverAttachmentEncoder(maxBytes = 4, streamOpener = { reads++; ByteArrayInputStream(ByteArray(10)) })
        val attachment = Attachment("a", "large.txt", "text/plain", 5, "content://private/large")
        try {
            encoder.encode(attachment)
            throw AssertionError("expected UnsupportedAttachment")
        } catch (error: UnsupportedAttachment) {
            assertTrue(reads == 0)
            assertTrue(!error.message.orEmpty().contains("private"))
        }
    }

    @Test fun actualStreamOverLimitFailsAndStopsAfterSentinelRead() {
        val source = ByteArray(6) { it.toByte() }
        var bytesRead = 0
        val encoder = ContentResolverAttachmentEncoder(maxBytes = 4, streamOpener = {
            object : InputStream() {
                private var index = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (index == source.size) return -1
                    val count = minOf(length, source.size - index)
                    source.copyInto(buffer, offset, index, index + count)
                    index += count
                    bytesRead += count
                    return count
                }
                override fun read(): Int = if (index == source.size) -1 else source[index++].toInt()
            }
        })
        val attachment = Attachment("a", "stream.txt", "text/plain", 4, "content://stream")
        try {
            encoder.encode(attachment)
            throw AssertionError("expected AttachmentReadError")
        } catch (error: AttachmentReadError) {
            assertTrue(bytesRead == 5)
            assertTrue(!error.message.orEmpty().contains("stream"))
        }
    }

    @Test fun exactlyMaximumSizeReadsSuccessfullyWithSentinelEof() {
        val maxBytes = AttachmentValidator.MAX_SIZE_BYTES
        val encoder = ContentResolverAttachmentEncoder(
            streamOpener = { ByteArrayInputStream(ByteArray(maxBytes.toInt()) { 7 }) },
        )
        val attachment = Attachment("a", "exact.txt", "text/plain", maxBytes, "content://exact")

        val encoded = encoder.encode(attachment).toString()

        assertTrue(encoded.contains("\"type\":\"text\""))
    }

    @Test fun unsupportedPdfDoesNotProduceUndefinedFilePayload() {
        val encoder = ContentResolverAttachmentEncoder(streamOpener = { ByteArrayInputStream("pdf".toByteArray()) })
        val attachment = Attachment("a", "file.pdf", "application/pdf", 3, "content://pdf")

        org.junit.Assert.assertThrows(UnsupportedAttachment::class.java) { encoder.encode(attachment) }
    }

    @Test fun imageUsesNestedImageUrlDataUri() {
        val encoder = ContentResolverAttachmentEncoder(streamOpener = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })
        val attachment = Attachment("a", "image.png", "image/png", 3, "content://image")

        val encoded = encoder.encode(attachment).toString()

        assertTrue(encoded.contains("\"type\":\"image_url\""))
        assertTrue(encoded.contains("\"image_url\":{\"url\":\"data:image/png;base64,"))
    }

    @Test fun cancellationIsNotMappedToAttachmentReadError() {
        val cancellation = CancellationException("cancelled")
        val encoder = ContentResolverAttachmentEncoder(streamOpener = { throw cancellation })
        val attachment = Attachment("a", "cancelled.txt", "text/plain", 1, "content://cancelled")
        try {
            encoder.encode(attachment)
            throw AssertionError("expected cancellation")
        } catch (error: CancellationException) {
            assertTrue(error === cancellation)
        }
    }
}
