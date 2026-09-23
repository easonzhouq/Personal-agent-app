package com.example.agentchat.data.attachment

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.example.agentchat.domain.model.Attachment
import java.util.UUID

object AttachmentPicker {
    val mimeTypes = arrayOf("image/*", "text/plain", "text/markdown", "application/json")

    fun readMetadataResult(resolver: ContentResolver, uri: Uri): MetadataResult {
        val mime = resolver.getType(uri)?.takeIf { it.isNotBlank() }
            ?: return MetadataResult.Rejected(AttachmentRejectionReason.UNKNOWN_MIME)
        var name: String? = null
        var size = -1L
        val queried = runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                if (!cursor.isNull(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))) size = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
                true
            } else false
        } ?: false }.getOrElse { return MetadataResult.Rejected(AttachmentRejectionReason.METADATA_MISSING) }
        if (!queried || name.isNullOrBlank()) return MetadataResult.Rejected(AttachmentRejectionReason.METADATA_MISSING)
        if (size < 0) return MetadataResult.Rejected(AttachmentRejectionReason.SIZE_UNAVAILABLE)
        val attachment = Attachment(UUID.randomUUID().toString(), name!!, mime, size, uri.toString())
        val validation = AttachmentValidator.validate(listOf(attachment), defaultConfig)
        return if (validation.isValid) MetadataResult.Accepted(attachment)
        else MetadataResult.Rejected(validation.reason!!.toRejectionReason())
    }

    fun takePersistablePermission(resolver: ContentResolver, uri: Uri): Boolean = runCatching {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.isSuccess

    fun releasePersistablePermission(resolver: ContentResolver, uri: Uri) {
        runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private val defaultConfig = com.example.agentchat.domain.model.ModelConfig("picker", "picker", "", "", com.example.agentchat.domain.model.ProviderProtocol.CUSTOM, supportsVision = true, supportsFiles = true)
}

enum class AttachmentRejectionReason { UNKNOWN_MIME, SIZE_UNAVAILABLE, METADATA_MISSING, PERSISTABLE_PERMISSION_FAILED, DUPLICATE_URI, TOO_MANY, TOO_LARGE }
data class AttachmentRejection(val uri: String, val reason: AttachmentRejectionReason)
data class AttachmentPickResult(val accepted: List<Attachment>, val rejected: List<AttachmentRejection>)
sealed interface MetadataResult { data class Accepted(val attachment: Attachment) : MetadataResult; data class Rejected(val reason: AttachmentRejectionReason) : MetadataResult }

private fun com.example.agentchat.data.attachment.AttachmentValidationReason.toRejectionReason() = when (this) {
    com.example.agentchat.data.attachment.AttachmentValidationReason.UNKNOWN_MIME -> AttachmentRejectionReason.UNKNOWN_MIME
    com.example.agentchat.data.attachment.AttachmentValidationReason.SIZE_UNAVAILABLE -> AttachmentRejectionReason.SIZE_UNAVAILABLE
    com.example.agentchat.data.attachment.AttachmentValidationReason.TOO_LARGE -> AttachmentRejectionReason.TOO_LARGE
    else -> AttachmentRejectionReason.METADATA_MISSING
}

fun pickAttachmentUris(
    uris: List<Uri>,
    takePermission: (Uri) -> Boolean,
    readMetadata: (Uri) -> MetadataResult,
): AttachmentPickResult {
    val acceptedCandidates = mutableListOf<Attachment>()
    val rejected = mutableListOf<AttachmentRejection>()
    val seen = mutableSetOf<String>()
    uris.forEach { uri ->
        val key = uri.toString()
        when {
            !seen.add(key) -> rejected += AttachmentRejection(key, AttachmentRejectionReason.DUPLICATE_URI)
            acceptedCandidates.size >= AttachmentValidator.MAX_ATTACHMENTS -> rejected += AttachmentRejection(key, AttachmentRejectionReason.TOO_MANY)
            else -> when (val result = readMetadata(uri)) {
                is MetadataResult.Accepted -> acceptedCandidates += result.attachment
                is MetadataResult.Rejected -> rejected += AttachmentRejection(key, result.reason)
            }
        }
    }
    val accepted = acceptedCandidates.filter { attachment ->
        if (takePermission(Uri.parse(attachment.contentUri))) true
        else {
            rejected += AttachmentRejection(attachment.contentUri, AttachmentRejectionReason.PERSISTABLE_PERMISSION_FAILED)
            false
        }
    }
    return AttachmentPickResult(accepted, rejected)
}

fun AttachmentRejectionReason.userMessage() = when (this) {
    AttachmentRejectionReason.UNKNOWN_MIME -> "附件类型不支持"
    AttachmentRejectionReason.SIZE_UNAVAILABLE -> "无法读取附件大小"
    AttachmentRejectionReason.METADATA_MISSING -> "附件元数据缺失"
    AttachmentRejectionReason.PERSISTABLE_PERMISSION_FAILED -> "无法获得附件持久化权限"
    AttachmentRejectionReason.DUPLICATE_URI -> "重复附件已忽略"
    AttachmentRejectionReason.TOO_MANY -> "最多添加 5 个附件"
    AttachmentRejectionReason.TOO_LARGE -> "附件不能超过 10 MiB"
}

@Composable
fun rememberAttachmentPicker(onPicked: (AttachmentPickResult) -> Unit): () -> Unit {
    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onPicked(pickAttachmentUris(
            uris,
            takePermission = { uri -> AttachmentPicker.takePersistablePermission(resolver, uri) },
            readMetadata = { uri -> AttachmentPicker.readMetadataResult(resolver, uri) },
        ))
    }
    return remember(launcher) { { launcher.launch(AttachmentPicker.mimeTypes) } }
}
