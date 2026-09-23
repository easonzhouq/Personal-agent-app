package com.example.agentchat.data.attachment

import com.example.agentchat.domain.model.Attachment
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Releases a persisted URI only after both history and draft references are gone. */
class AttachmentReferenceCoordinator(
    private val historicalReferenceCount: suspend (String) -> Int,
    private val releasePersistedUri: (String) -> Unit,
) {
    private val mutex = Mutex()

    suspend fun <T> withReferenceLock(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun releaseIfUnreferenced(contentUri: String, draftAttachments: List<Attachment>) {
        releaseIfUnreferenced(contentUri, draftAttachments.map { it.contentUri }.toSet())
    }

    suspend fun releaseIfUnreferenced(contentUri: String, draftContentUris: Set<String>) {
        mutex.withLock { releaseIfUnreferencedWhileLocked(contentUri, draftContentUris) }
    }

    suspend fun releaseIfUnreferencedWhileLocked(contentUri: String, draftContentUris: Set<String>) {
        if (contentUri !in draftContentUris && historicalReferenceCount(contentUri) == 0) releasePersistedUri(contentUri)
    }
}
