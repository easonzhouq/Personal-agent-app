package com.example.agentchat.data.attachment

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.content.ContentResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agentchat.domain.model.Attachment
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentResolverAttachmentEncoderAndroidTest {
    private lateinit var file: File
    private lateinit var resolver: ContentResolver

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File.createTempFile("attachment", ".txt", context.cacheDir)
        FileOutputStream(file).use { it.write("hello from content resolver".toByteArray()) }
        resolver = ContentResolver.wrap(FileContentProvider(file))
    }

    @After fun tearDown() { file.delete() }

    @Test fun encodesContentResolverContentUriThroughOpenInputStream() {
        val encoder = ContentResolverAttachmentEncoder(resolver = resolver)
        val attachment = Attachment("a", "note.txt", "text/plain", file.length(), "content://task7/note")
        val encoded = encoder.encode(attachment).toString()
        assertTrue(encoded.contains("\"type\":\"text\""))
        assertTrue(encoded.contains("hello from content resolver"))
    }

    private class FileContentProvider(private val file: File) : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/plain"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(projection ?: emptyArray())
        override fun insert(uri: Uri, values: ContentValues?) = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
