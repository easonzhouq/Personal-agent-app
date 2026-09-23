package com.example.agentchat.data.attachment

import android.net.Uri
import com.example.agentchat.domain.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentPickerTest {
    @Test fun pickerReportsDuplicateAndTooManyInsteadOfDroppingUris() {
        val uris = (0..5).map { Uri.parse("content://file/$it") } + Uri.parse("content://file/0")
        val result = pickAttachmentUris(uris, { true }) { uri ->
            MetadataResult.Accepted(Attachment(uri.toString(), "file", "text/plain", 1, uri.toString()))
        }
        assertEquals(5, result.accepted.size)
        assertEquals(AttachmentRejectionReason.TOO_MANY, result.rejected[0].reason)
        assertEquals(AttachmentRejectionReason.DUPLICATE_URI, result.rejected[1].reason)
    }

    @Test fun pickerReportsPermissionFailureAndMetadataReasons() {
        val uris = listOf(Uri.parse("content://permission"), Uri.parse("content://metadata"))
        val result = pickAttachmentUris(uris, { it.toString() != "content://permission" }) {
            if (it.toString() == "content://permission") {
                MetadataResult.Accepted(Attachment(it.toString(), "permission.txt", "text/plain", 1, it.toString()))
            } else MetadataResult.Rejected(AttachmentRejectionReason.METADATA_MISSING)
        }
        assertEquals(listOf(AttachmentRejectionReason.PERSISTABLE_PERMISSION_FAILED, AttachmentRejectionReason.METADATA_MISSING), result.rejected.map { it.reason })
    }

    @Test fun pickerReportsUnknownMimeAndUnknownSizeBeforeRequestingPermission() {
        val requested = mutableListOf<String>()
        val result = pickAttachmentUris(
            listOf(Uri.parse("content://unknown-mime"), Uri.parse("content://unknown-size")),
            takePermission = { requested += it.toString(); true },
        ) { uri ->
            if (uri.toString().endsWith("mime")) MetadataResult.Rejected(AttachmentRejectionReason.UNKNOWN_MIME)
            else MetadataResult.Rejected(AttachmentRejectionReason.SIZE_UNAVAILABLE)
        }
        assertEquals(listOf(AttachmentRejectionReason.UNKNOWN_MIME, AttachmentRejectionReason.SIZE_UNAVAILABLE), result.rejected.map { it.reason })
        assertEquals(emptyList<String>(), requested)
    }
}
