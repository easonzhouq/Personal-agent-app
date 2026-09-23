package com.example.agentchat.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agentchat.domain.model.Attachment
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.MessageStatus
import com.example.agentchat.domain.model.Role
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalHistoryRepositoryTest {
    private lateinit var database: AgentDatabase
    private lateinit var repository: LocalHistoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        repository = LocalHistoryRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAppendReadAndSearchConversation() = runBlocking {
        val conversationId = UUID.randomUUID().toString()
        val messageId = UUID.randomUUID().toString()
        repository.appendMessage(
            ChatMessage(
                id = messageId,
                conversationId = conversationId,
                role = Role.USER,
                text = "hello room",
                status = MessageStatus.COMPLETED,
            ),
        )

        val conversation = repository.observeConversations().first().single()
        assertEquals(conversationId, conversation.id)
        assertEquals("hello room", conversation.title)
        assertEquals(listOf(messageId), repository.observeMessages(conversationId).first().map { it.id })
        assertEquals(listOf(conversationId), repository.search("ROOM").map { it.id })
    }

    @Test
    fun searchFindsConversationWhenOnlyMessageTextMatches() = runBlocking {
        val conversationId = UUID.randomUUID().toString()
        repository.appendMessage(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = Role.USER,
                text = "body-only search term",
                status = MessageStatus.COMPLETED,
            ),
        )

        assertEquals(listOf(conversationId), repository.search("body-only").map { it.id })
    }

    @Test
    fun appendMessageReturnsUuidIdsUsableForUpdateAndRead() = runBlocking {
        val inserted = repository.appendMessage(
            ChatMessage(
                id = "message-id-from-network",
                conversationId = "conversation-id-from-network",
                role = Role.USER,
                text = "new",
                attachments = listOf(Attachment("attachment-id-from-network", "x", "text/plain", 1, "content://x")),
                status = MessageStatus.PENDING,
            ),
        )

        UUID.fromString(inserted.id)
        UUID.fromString(inserted.conversationId)
        UUID.fromString(inserted.attachments.single().id)

        repository.updateAssistantMessage(inserted.id, "updated", MessageStatus.COMPLETED)

        val stored = repository.observeMessages(inserted.conversationId).first().single()
        assertEquals(inserted.id, stored.id)
        assertEquals("updated", stored.text)
        assertEquals(MessageStatus.COMPLETED, stored.status)
        assertEquals(inserted.attachments.single().id, stored.attachments.single().id)

        repository.deleteConversation(inserted.conversationId, emptySet())
        assertTrue(repository.observeMessages(inserted.conversationId).first().isEmpty())
    }

    @Test
    fun appendMessagePersistsAttachmentsAndAssistantCanBeUpdated() = runBlocking {
        val conversationId = UUID.randomUUID().toString()
        val assistantId = UUID.randomUUID().toString()
        repository.appendMessage(
            ChatMessage(
                id = assistantId,
                conversationId = conversationId,
                role = Role.ASSISTANT,
                text = "thinking",
                attachments = listOf(Attachment(UUID.randomUUID().toString(), "note.txt", "text/plain", 4, "content://note")),
                status = MessageStatus.STREAMING,
            ),
        )

        repository.updateAssistantMessage(assistantId, "done", MessageStatus.COMPLETED)

        val message = repository.observeMessages(conversationId).first().single()
        assertEquals("done", message.text)
        assertEquals(MessageStatus.COMPLETED, message.status)
        assertEquals("note.txt", message.attachments.single().name)
    }

    @Test
    fun deletingSharedUriReleasesOnlyAfterLastHistoryReference() = runBlocking {
        val released = mutableListOf<String>()
        val sharedRepository = LocalHistoryRepository(database, releasePersistedUri = { released += it })
        val attachment = Attachment("shared", "shared.txt", "text/plain", 1, "content://shared")
        val firstConversation = UUID.randomUUID().toString()
        val secondConversation = UUID.randomUUID().toString()
        sharedRepository.appendMessage(ChatMessage(UUID.randomUUID().toString(), firstConversation, Role.USER, "one", listOf(attachment), MessageStatus.COMPLETED))
        sharedRepository.appendMessage(ChatMessage(UUID.randomUUID().toString(), secondConversation, Role.USER, "two", listOf(attachment.copy(id = "shared-2")), MessageStatus.COMPLETED))

        sharedRepository.deleteConversation(firstConversation, draftContentUris = setOf("content://shared"))
        assertTrue(released.isEmpty())
        assertEquals(1, sharedRepository.countAttachmentReferences("content://shared"))

        sharedRepository.deleteConversation(secondConversation, emptySet())
        assertEquals(listOf("content://shared"), released)
        assertEquals(0, sharedRepository.countAttachmentReferences("content://shared"))
    }

    @Test
    fun missingAssistantIdFailsWithoutUpdatingConversationTimestamp() = runBlocking {
        val inserted = repository.appendMessage(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = UUID.randomUUID().toString(),
                role = Role.USER,
                text = "unchanged",
                status = MessageStatus.COMPLETED,
            ),
        )
        val before = repository.observeConversations().first().single()

        assertThrows(MessageNotFoundException::class.java) {
            runBlocking { repository.updateAssistantMessage(UUID.randomUUID().toString(), "must not persist", MessageStatus.COMPLETED) }
        }

        val after = repository.observeConversations().first().single()
        assertEquals(before.updatedAt, after.updatedAt)
        assertEquals("unchanged", repository.observeMessages(inserted.conversationId).first().single().text)
    }

    @Test
    fun observeMessagesIncludesAttachmentsAndReactsToAttachmentChanges() = runBlocking {
        val inserted = repository.appendMessage(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = UUID.randomUUID().toString(),
                role = Role.USER,
                text = "attachment flow",
                attachments = listOf(Attachment(UUID.randomUUID().toString(), "first", "text/plain", 1, "content://first")),
                status = MessageStatus.COMPLETED,
            ),
        )

        assertEquals(1, repository.observeMessages(inserted.conversationId).first().single().attachments.size)
        database.attachmentDao().insertAll(
            listOf(
                AttachmentEntity(
                    id = UUID.randomUUID().toString(),
                    messageId = inserted.id,
                    name = "second",
                    mimeType = "text/plain",
                    sizeBytes = 2,
                    contentUri = "content://second",
                ),
            ),
        )

        val updated = repository.observeMessages(inserted.conversationId).first { it.single().attachments.size == 2 }
        assertEquals(setOf("first", "second"), updated.single().attachments.map { it.name }.toSet())
    }

    @Test
    fun normalizeForInsertCanonicalizesUppercaseUuid() = runBlocking {
        val uppercaseId = UUID.randomUUID().toString().uppercase()
        val inserted = repository.appendMessage(
            ChatMessage(
                id = uppercaseId,
                conversationId = uppercaseId,
                role = Role.USER,
                text = "canonical",
                status = MessageStatus.PENDING,
            ),
        )

        assertEquals(uppercaseId.lowercase(), inserted.id)
        assertEquals(uppercaseId.lowercase(), inserted.conversationId)
    }

    @Test
    fun unknownAndLowercaseRoleAndStatusUseSafeMappings() = runBlocking {
        val conversationId = UUID.randomUUID().toString()
        database.conversationDao().insert(ConversationEntity(conversationId, "mapping", 1L, 1L))
        database.messageDao().insert(
            MessageEntity(UUID.randomUUID().toString(), conversationId, "assistant", "lower", "completed", 1L),
        )
        database.messageDao().insert(
            MessageEntity(UUID.randomUUID().toString(), conversationId, "unknown", "unknown role", "unknown", 2L),
        )

        val messages = repository.observeMessages(conversationId).first()
        assertEquals(Role.ASSISTANT, messages[0].role)
        assertEquals(MessageStatus.COMPLETED, messages[0].status)
        assertEquals(Role.USER, messages[1].role)
        assertEquals(MessageStatus.FAILED, messages[1].status)
    }

    @Test
    fun concurrentFirstMessagesDoNotDeleteEachOther() = runBlocking {
        val conversationId = UUID.randomUUID().toString()
        val inserted = coroutineScope {
            listOf("one", "two").map { text ->
                async {
                    repository.appendMessage(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            role = Role.USER,
                            text = text,
                            status = MessageStatus.COMPLETED,
                        ),
                    )
                }
            }.awaitAll()
        }

        assertEquals(2, inserted.size)
        assertEquals(2, repository.observeMessages(conversationId).first().size)
    }

    @Test
    fun clearAllLocalDataRemovesHistoryAndReleasesUniqueUris() = runBlocking {
        val released = mutableListOf<String>()
        val clearing = LocalHistoryRepository(database, releasePersistedUri = { released += it })
        val attachment = Attachment("a", "note.txt", "text/plain", 1, "content://clear")
        clearing.appendMessage(ChatMessage(UUID.randomUUID().toString(), UUID.randomUUID().toString(), Role.USER, "clear", listOf(attachment), MessageStatus.COMPLETED))

        clearing.clearAllLocalData(setOf("content://clear"))

        assertTrue(clearing.observeConversations().first().isEmpty())
        assertEquals(listOf("content://clear"), released)
    }

    @Test
    fun deleteConversationCascadesMessagesAndAttachments() = runBlocking {
        val conversationId = UUID.randomUUID().toString()
        repository.appendMessage(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = Role.USER,
                text = "remove me",
                attachments = listOf(Attachment(UUID.randomUUID().toString(), "x", "text/plain", 1, "content://x")),
                status = MessageStatus.COMPLETED,
            ),
        )

        repository.deleteConversation(conversationId, emptySet())

        assertTrue(repository.observeConversations().first().isEmpty())
        assertTrue(repository.observeMessages(conversationId).first().isEmpty())
        assertEquals(0, database.attachmentDao().count())
    }
}
