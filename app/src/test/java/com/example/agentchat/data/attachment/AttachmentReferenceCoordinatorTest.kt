package com.example.agentchat.data.attachment

import com.example.agentchat.domain.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentReferenceCoordinatorTest {
    @Test fun sharedHistoryUriReleasesOnlyAfterLastReference() = kotlinx.coroutines.test.runTest {
        var historyCount = 2
        val released = mutableListOf<String>()
        val coordinator = AttachmentReferenceCoordinator({ historyCount }, { released += it })
        coordinator.releaseIfUnreferenced("content://shared", emptyList())
        assertEquals(emptyList<String>(), released)
        historyCount = 1
        coordinator.releaseIfUnreferenced("content://shared", emptyList())
        assertEquals(emptyList<String>(), released)
        historyCount = 0
        coordinator.releaseIfUnreferenced("content://shared", emptyList())
        assertEquals(listOf("content://shared"), released)
    }

    @Test fun draftReferencePreventsReleaseEvenWithoutHistory() = kotlinx.coroutines.test.runTest {
        val released = mutableListOf<String>()
        val coordinator = AttachmentReferenceCoordinator({ 0 }, { released += it })
        val draft = Attachment("a", "shared.txt", "text/plain", 1, "content://shared")
        coordinator.releaseIfUnreferenced(draft.contentUri, listOf(draft))
        assertEquals(emptyList<String>(), released)
    }
}
