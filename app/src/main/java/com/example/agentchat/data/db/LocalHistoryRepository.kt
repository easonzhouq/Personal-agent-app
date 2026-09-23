package com.example.agentchat.data.db

import android.content.ContentResolver
import android.net.Uri
import com.example.agentchat.data.attachment.AttachmentPicker
import com.example.agentchat.data.attachment.AttachmentReferenceCoordinator
import com.example.agentchat.domain.model.Attachment
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.Conversation
import com.example.agentchat.domain.model.MessageStatus
import com.example.agentchat.domain.model.Role
import com.example.agentchat.ui.history.HistoryRepositoryPort
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

class LocalHistoryRepository(
    private val database: AgentDatabase,
    private val contentResolver: ContentResolver? = null,
    private val releasePersistedUri: (String) -> Unit = { contentUri ->
        contentResolver?.let { resolver ->
            AttachmentPicker.releasePersistablePermission(resolver, Uri.parse(contentUri))
        }
    },
) : HistoryRepositoryPort {
    private val conversations = database.conversationDao()
    private val messages = database.messageDao()
    private val attachments = database.attachmentDao()
    private val references = AttachmentReferenceCoordinator(
        historicalReferenceCount = ::countAttachmentReferences,
        releasePersistedUri = releasePersistedUri,
    )

    override fun observeConversations(): Flow<List<Conversation>> = conversations.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        messages.observeWithAttachments(conversationId).map { list ->
            list.map { messageWithAttachments ->
                messageWithAttachments.message.toDomain(messageWithAttachments.attachments.map { it.toDomain() })
            }
        }

    override suspend fun search(query: String): List<Conversation> = conversations.search(query).map { it.toDomain() }

    suspend fun retrieveRelevantMessages(query: String, excludeConversationId: String, limit: Int = 6): List<ChatMessage> {
        val terms = queryTerms(query)
        if (terms.isEmpty()) return emptyList()
        return messages.findRecentCompleted(500)
            .asSequence()
            .filter { it.conversationId != excludeConversationId && it.text.isNotBlank() }
            .map { entity ->
                val normalized = entity.text.lowercase()
                val score = terms.count { term -> normalized.contains(term) }
                entity to score
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MessageEntity, Int>> { it.second }.thenByDescending { it.first.createdAt })
            .take(limit)
            .map { it.first.toDomain(emptyList()) }
            .toList()
    }

    suspend fun countAttachmentReferences(contentUri: String): Int = attachments.countByContentUri(contentUri)

    fun attachmentReferenceCoordinator() = references

    suspend fun appendMessage(message: ChatMessage): ChatMessage {
        return references.withReferenceLock {
            val now = System.currentTimeMillis()
            val normalized = normalizeForInsert(message).copy(createdAt = message.createdAt.takeIf { it > 0 } ?: now)
            database.withTransaction {
                val existing = conversations.findById(normalized.conversationId)
                if (existing == null) {
                    conversations.insert(ConversationEntity(normalized.conversationId, normalized.text, now, now))
                } else {
                    conversations.updateTitleAndTimestamp(existing.id, existing.title, now)
                }
                messages.insert(normalized.toEntity(now))
                attachments.insertAll(normalized.attachments.map { it.toEntity(normalized.id) })
            }
            normalized
        }
    }

    /** Returns a new-message model whose IDs are safe for insertion. */
    fun normalizeForInsert(message: ChatMessage): ChatMessage = message.copy(
        id = message.id.uuidForInsert(),
        conversationId = message.conversationId.uuidForInsert(),
        attachments = message.attachments.map { attachment ->
            attachment.copy(id = attachment.id.uuidForInsert())
        },
    )

    suspend fun updateAssistantMessage(id: String, text: String, status: MessageStatus) {
        database.withTransaction {
            val updatedRows = messages.updateTextAndStatus(id, text, status.name)
            if (updatedRows != 1) {
                throw MessageNotFoundException(id)
            }

            val message = messages.findById(id) ?: throw MessageNotFoundException(id)
            val conversation = conversations.findById(message.conversationId)
                ?: throw ConversationNotFoundException(message.conversationId)
            val conversationRows = conversations.updateTitleAndTimestamp(
                conversation.id,
                conversation.title,
                System.currentTimeMillis(),
            )
            if (conversationRows != 1) {
                throw ConversationNotFoundException(conversation.id)
            }
        }
    }

    override suspend fun deleteConversation(id: String, draftContentUris: Set<String>) {
        references.withReferenceLock {
            val uris = observeMessages(id).first().flatMap { it.attachments }.map { it.contentUri }.distinct()
            database.withTransaction { conversations.findById(id)?.let { conversations.delete(it) } }
            uris.forEach { references.releaseIfUnreferencedWhileLocked(it, draftContentUris) }
        }
    }

    override suspend fun clearAllLocalData(draftContentUris: Set<String>) {
        references.withReferenceLock {
            val uris = attachments.findAllContentUris().toSet() + draftContentUris
            database.withTransaction {
                attachments.deleteAll()
                messages.deleteAll()
                conversations.deleteAll()
            }
            uris.forEach { uri -> references.releaseIfUnreferencedWhileLocked(uri, emptySet()) }
        }
    }

    private fun ConversationEntity.toDomain() = Conversation(id, title, createdAt, updatedAt)

    private fun MessageEntity.toDomain(attachmentList: List<Attachment>) = ChatMessage(
        id = id,
        conversationId = conversationId,
        role = role.toRoleOrDefault(),
        text = text,
        attachments = attachmentList,
        status = status.toStatusOrDefault(),
        createdAt = createdAt,
    )

    private fun queryTerms(value: String): Set<String> {
        val lower = value.lowercase()
        val cjk = lower.filter { it in '\u4e00'..'\u9fff' }
        val cjkTerms = cjk.windowed(size = 2, step = 1, partialWindows = false).toSet()
        val latinTerms = Regex("[a-z0-9]{2,}").findAll(lower).map { it.value }.toSet()
        return cjkTerms + latinTerms
    }

    private fun ChatMessage.toEntity(createdAt: Long) = MessageEntity(
        id, conversationId, role.name, text, status.name, createdAt,
    )

    private fun Attachment.toEntity(messageId: String) = AttachmentEntity(
        id = id,
        messageId = messageId,
        name = name,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        contentUri = contentUri,
    )

    private fun AttachmentEntity.toDomain() = Attachment(id, name, mimeType, sizeBytes, contentUri)

    private fun String.uuidForInsert(): String =
        runCatching { UUID.fromString(this).toString() }.getOrElse { UUID.randomUUID().toString() }

    private fun String.toRoleOrDefault(): Role =
        Role.values().firstOrNull { it.name.equals(this, ignoreCase = true) } ?: Role.USER

    private fun String.toStatusOrDefault(): MessageStatus =
        MessageStatus.values().firstOrNull { it.name.equals(this, ignoreCase = true) } ?: MessageStatus.FAILED
}

class MessageNotFoundException(id: String) : IllegalStateException("Message not found: $id")

class ConversationNotFoundException(id: String) : IllegalStateException("Conversation not found: $id")
