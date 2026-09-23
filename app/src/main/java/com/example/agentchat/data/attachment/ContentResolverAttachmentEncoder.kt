package com.example.agentchat.data.attachment

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import com.example.agentchat.data.provider.AttachmentEncoder
import com.example.agentchat.domain.model.Attachment
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException

class UnsupportedAttachment(message: String) : IOException(message)
class AttachmentReadError(val reasonCode: String = "read_failed") : IOException("Unable to read attachment")

class ContentResolverAttachmentEncoder(
    private val resolver: ContentResolver? = null,
    private val maxBytes: Long = AttachmentValidator.MAX_SIZE_BYTES,
    private val streamOpener: ((Uri) -> java.io.InputStream?)? = null,
) : AttachmentEncoder {
    override fun encode(attachment: Attachment): JsonElement {
        if (attachment.sizeBytes < 0 || attachment.sizeBytes > maxBytes) throw UnsupportedAttachment("Attachment size is not supported")
        val bytes = try {
            (streamOpener ?: resolver?.let { it::openInputStream } ?: throw AttachmentReadError())(Uri.parse(attachment.contentUri))?.use { input ->
                java.io.ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val remaining = maxBytes - total
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining + 1).toInt())
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) throw AttachmentReadError()
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } ?: throw AttachmentReadError()
        } catch (error: UnsupportedAttachment) {
            throw error
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            throw AttachmentReadError()
        }
        return buildJsonObject {
            val mimeType = attachment.mimeType.lowercase(Locale.ROOT)
            val dataUri = "data:${attachment.mimeType};base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
            when {
                mimeType.startsWith("image/") -> {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", dataUri) })
                }
                mimeType in setOf("text/plain", "text/markdown", "application/json") -> {
                    put("type", "text")
                    put("text", bytes.toString(Charsets.UTF_8))
                }
                else -> throw UnsupportedAttachment("Attachment type is not supported")
            }
        }
    }
}
