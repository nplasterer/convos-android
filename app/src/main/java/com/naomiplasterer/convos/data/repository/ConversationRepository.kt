package com.naomiplasterer.convos.data.repository

import com.naomiplasterer.convos.data.local.dao.ConversationDao
import com.naomiplasterer.convos.data.local.dao.MemberDao
import com.naomiplasterer.convos.data.mapper.toEntity
import com.naomiplasterer.convos.data.mapper.toDomain
import com.naomiplasterer.convos.data.xmtp.XMTPClientManager
import com.naomiplasterer.convos.domain.model.Conversation
import com.naomiplasterer.convos.domain.model.ConsentState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val memberDao: MemberDao,
    private val xmtpClientManager: XMTPClientManager
) {

    fun getConversations(inboxId: String): Flow<List<Conversation>> {
        return conversationDao.getAllowedConversations(inboxId)
            .map { entities -> entities.map { it.toDomain() } }
    }

    fun getConversation(conversationId: String): Flow<Conversation?> {
        return conversationDao.getConversation(conversationId)
            .map { it?.toDomain() }
    }

    suspend fun syncConversations(inboxId: String): Result<Unit> {
        return try {
            val client = xmtpClientManager.getClient(inboxId)
                ?: return Result.failure(IllegalStateException("No client for inbox: $inboxId"))

            client.conversations.sync()

            val conversations = client.conversations.list()

            val entities = conversations.map { it.toEntity(inboxId) }
            conversationDao.insertAll(entities)

            Timber.d("Synced ${entities.size} conversations for inbox: $inboxId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync conversations")
            Result.failure(e)
        }
    }

    suspend fun updateConsent(conversationId: String, consent: ConsentState) {
        try {
            val consentString = when (consent) {
                ConsentState.ALLOWED -> "allowed"
                ConsentState.DENIED -> "denied"
                ConsentState.UNKNOWN -> "unknown"
            }
            conversationDao.updateConsent(conversationId, consentString)
            Timber.d("Updated consent for conversation: $conversationId to $consent")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update consent")
        }
    }

    suspend fun updatePinned(conversationId: String, isPinned: Boolean) {
        try {
            conversationDao.updatePinned(conversationId, isPinned)
            Timber.d("Updated pinned state for conversation: $conversationId to $isPinned")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update pinned state")
        }
    }

    suspend fun updateMuted(conversationId: String, isMuted: Boolean) {
        try {
            conversationDao.updateMuted(conversationId, isMuted)
            Timber.d("Updated muted state for conversation: $conversationId to $isMuted")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update muted state")
        }
    }

    suspend fun updateUnread(conversationId: String, isUnread: Boolean) {
        try {
            conversationDao.updateUnread(conversationId, isUnread)
            Timber.d("Updated unread state for conversation: $conversationId to $isUnread")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update unread state")
        }
    }

    suspend fun updateMetadata(
        conversationId: String,
        name: String?,
        description: String?,
        imageUrl: String?
    ) {
        try {
            conversationDao.updateMetadata(conversationId, name, description, imageUrl)
            Timber.d("Updated metadata for conversation: $conversationId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update metadata")
        }
    }

    suspend fun deleteConversation(conversationId: String) {
        try {
            val conversation = conversationDao.getConversationSync(conversationId)
            if (conversation != null) {
                conversationDao.delete(conversation.toDomain().toEntity())
                Timber.d("Deleted conversation: $conversationId")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete conversation")
        }
    }
}
