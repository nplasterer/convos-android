package com.naomiplasterer.convos.data.repository

import com.naomiplasterer.convos.data.invite.InviteJoinRequestsManager
import com.naomiplasterer.convos.data.local.dao.ConversationDao
import com.naomiplasterer.convos.data.local.dao.InboxDao
import com.naomiplasterer.convos.data.local.dao.MemberDao
import com.naomiplasterer.convos.data.local.dao.MemberProfileDao
import com.naomiplasterer.convos.data.local.entity.MemberProfileEntity
import com.naomiplasterer.convos.data.mapper.toEntity
import com.naomiplasterer.convos.data.mapper.toDomain
import com.naomiplasterer.convos.data.xmtp.XMTPClientManager
import com.naomiplasterer.convos.domain.model.Conversation
import com.naomiplasterer.convos.domain.model.ConsentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.xmtp.android.library.Conversation as XMTPConversation
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ConversationRepository"

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val inboxDao: InboxDao,
    private val memberDao: MemberDao,
    private val memberProfileDao: MemberProfileDao,
    private val xmtpClientManager: XMTPClientManager,
    private val inviteJoinRequestsManager: InviteJoinRequestsManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getConversations(inboxId: String): Flow<List<Conversation>> {
        return conversationDao.getAllowedConversations(inboxId)
            .map { entities ->
                Log.d(TAG, "getConversations: Found ${entities.size} allowed conversations for inbox: $inboxId")
                entities.forEach { entity ->
                    Log.d(TAG, "  - Conversation ${entity.id}: consent=${entity.consent}, isDraft=${entity.isDraft}, name=${entity.name}")
                }
                entities.map { it.toDomain() }
            }
    }

    fun getConversation(conversationId: String): Flow<Conversation?> {
        return conversationDao.getConversation(conversationId)
            .map { it?.toDomain() }
    }

    fun getConversationsFromAllInboxes(inboxIds: List<String>): Flow<List<Conversation>> {
        return conversationDao.getAllowedConversationsFromAllInboxes(inboxIds)
            .map { entities ->
                Log.d(TAG, "getConversationsFromAllInboxes: Found ${entities.size} allowed conversations from ${inboxIds.size} inboxes")
                entities.map { it.toDomain() }
            }
            .catch { e ->
                Log.e(TAG, "Error getting conversations from all inboxes", e)
                emit(emptyList())
            }
    }

    suspend fun syncConversations(inboxId: String): Result<Unit> {
        return try {
            val client = xmtpClientManager.getClient(inboxId)
                ?: return Result.failure(IllegalStateException("No client for inbox: $inboxId"))

            client.conversations.sync()

            val conversations = client.conversations.list()
            val clientId = client.installationId

            // For each conversation, check if it exists and preserve consent state if it does
            for (conversation in conversations) {
                val existing = conversationDao.getConversationSync(conversation.id)

                // Get the base entity with group name/description/imageUrl from XMTP
                var entity = conversation.toEntity(inboxId).copy(clientId = clientId)

                // Extract metadata from appData/description if it's a group
                var expiresAt: Long? = null
                if (conversation is org.xmtp.android.library.Conversation.Group) {
                    try {
                        val metadata = com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.retrieveMetadata(conversation)
                        if (metadata != null) {
                            Log.d(TAG, "Extracted metadata from group ${conversation.id}: tag=${metadata.tag}, profiles=${metadata.profilesCount}")

                            // Extract expiresAt if present
                            if (metadata.hasExpiresAtUnix() && metadata.expiresAtUnix > 0) {
                                expiresAt = metadata.expiresAtUnix * 1000 // Convert to milliseconds
                            }

                            // Store member profiles to database
                            val profiles = com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.extractProfiles(metadata)
                            val profileEntities = profiles.map { (inboxId, profile) ->
                                MemberProfileEntity(
                                    conversationId = conversation.id,
                                    inboxId = inboxId,
                                    name = profile.name.takeIf { it.isNotBlank() },
                                    avatar = profile.image.takeIf { it.isNotBlank() }
                                )
                            }
                            memberProfileDao.insertAll(profileEntities)
                            Log.d(TAG, "Stored ${profileEntities.size} member profiles to database for conversation ${conversation.id}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to extract metadata from group ${conversation.id}", e)
                    }

                    // Always get the latest group name/description/imageUrl from XMTP
                    // These might have been updated by other members
                    val group = conversation.group
                    @Suppress("DEPRECATION")
                    val latestName = try { group.name?.takeIf { it.isNotBlank() } } catch (e: Exception) { null }
                    @Suppress("DEPRECATION")
                    val latestDescription = try { group.description } catch (e: Exception) { null }
                    @Suppress("DEPRECATION")
                    val latestImageUrl = try { group.imageUrl?.takeIf { it.isNotEmpty() } } catch (e: Exception) { null }

                    // Update entity with latest metadata
                    entity = entity.copy(
                        name = latestName ?: entity.name,
                        description = latestDescription ?: entity.description,
                        imageUrl = latestImageUrl ?: entity.imageUrl,
                        expiresAt = expiresAt
                    )

                    Log.d(TAG, "Group ${conversation.id} metadata: name='${entity.name}', description='${entity.description?.take(50)}', imageUrl='${entity.imageUrl}', expiresAt=$expiresAt")
                }

                if (existing != null) {
                    // Conversation exists - update metadata but preserve consent and user preferences
                    entity = entity.copy(
                        consent = existing.consent, // Preserve consent state
                        isPinned = existing.isPinned, // Preserve pinned state
                        isMuted = existing.isMuted, // Preserve muted state
                        isUnread = existing.isUnread // Preserve unread state
                    )
                    conversationDao.insert(entity)
                    Log.d(TAG, "Updated existing conversation: ${conversation.id}, consent=${existing.consent}, name='${entity.name}'")
                } else {
                    // New conversation - check XMTP consent state
                    val consentState = if (conversation is org.xmtp.android.library.Conversation.Dm) {
                        // For DMs, check the XMTP consent state
                        try {
                            conversation.consentState()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to get consent state for DM ${conversation.id}", e)
                            org.xmtp.android.library.ConsentState.UNKNOWN
                        }
                    } else {
                        // For groups, default to ALLOWED
                        org.xmtp.android.library.ConsentState.ALLOWED
                    }

                    val consent = when (consentState) {
                        org.xmtp.android.library.ConsentState.ALLOWED -> "allowed"
                        org.xmtp.android.library.ConsentState.DENIED -> "denied"
                        org.xmtp.android.library.ConsentState.UNKNOWN -> "allowed" // Default to allowed for unknown
                    }

                    entity = entity.copy(consent = consent)
                    conversationDao.insert(entity)
                    Log.d(TAG, "New conversation synced: ${conversation.id}, consent=$consent (XMTP consent: $consentState), name='${entity.name}'")
                }
            }

            Log.d(TAG, "Synced ${conversations.size} conversations for inbox: $inboxId")

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync conversations", e)
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
            Log.d(TAG, "Updated consent for conversation: $conversationId to $consent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update consent", e)
        }
    }

    suspend fun updatePinned(conversationId: String, isPinned: Boolean) {
        try {
            conversationDao.updatePinned(conversationId, isPinned)
            Log.d(TAG, "Updated pinned state for conversation: $conversationId to $isPinned")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update pinned state", e)
        }
    }

    suspend fun updateMuted(conversationId: String, isMuted: Boolean) {
        try {
            conversationDao.updateMuted(conversationId, isMuted)
            Log.d(TAG, "Updated muted state for conversation: $conversationId to $isMuted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update muted state", e)
        }
    }

    suspend fun updateUnread(conversationId: String, isUnread: Boolean) {
        try {
            conversationDao.updateUnread(conversationId, isUnread)
            Log.d(TAG, "Updated unread state for conversation: $conversationId to $isUnread")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update unread state", e)
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
            Log.d(TAG, "Updated metadata for conversation: $conversationId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update metadata", e)
        }
    }

    suspend fun updateConversationDetails(
        inboxId: String,
        conversationId: String,
        name: String?,
        description: String?,
        imageUrl: String?
    ): Result<Unit> {
        return try {
            val client = xmtpClientManager.getClient(inboxId)
                ?: return Result.failure(IllegalStateException("No client for inbox: $inboxId"))

            val group = client.conversations.findGroup(conversationId)
                ?: return Result.failure(IllegalStateException("Group not found: $conversationId"))

            name?.let { group.updateName(it) }
            description?.let { group.updateDescription(it) }
            imageUrl?.let { group.updateImageUrl(it) }

            updateMetadata(conversationId, name, description, imageUrl)

            Log.d(TAG, "Updated conversation details on XMTP")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update conversation details on XMTP", e)
            Result.failure(e)
        }
    }

    suspend fun deleteConversation(conversationId: String) {
        try {
            val conversation = conversationDao.getConversationSync(conversationId)
            if (conversation != null) {
                val inboxId = conversation.inboxId

                // Remove the XMTP client from memory
                try {
                    xmtpClientManager.removeClient(inboxId)
                    Log.d(TAG, "Removed XMTP client for conversation: $conversationId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove XMTP client", e)
                }

                // Delete the inbox (which will cascade delete the conversation via foreign key)
                try {
                    val inbox = inboxDao.getInbox(inboxId)
                    if (inbox != null) {
                        inboxDao.delete(inbox)
                        Log.d(TAG, "Deleted inbox and conversation: $conversationId")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete inbox", e)
                    // Fallback: try to delete just the conversation
                    conversationDao.delete(conversation.toDomain().toEntity())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete conversation", e)
        }
    }

    fun startConversationStreaming(inboxId: String) {
        scope.launch {
            try {
                val client = xmtpClientManager.getClient(inboxId)
                if (client == null) {
                    Log.e(TAG, "No client for inbox: $inboxId")
                    return@launch
                }

                Log.d(TAG, "Starting conversation streaming for inbox: $inboxId")

                val clientId = client.installationId

                client.conversations.stream()
                    .catch { e ->
                        Log.e(TAG, "Error in conversation stream", e)
                    }
                    .collect { conversation ->
                        Log.d(TAG, "Received new conversation via stream: ${conversation.id}")

                        // Get the base entity with group metadata
                        var entity = conversation.toEntity(inboxId).copy(clientId = clientId)

                        // For groups, extract latest metadata
                        var expiresAt: Long? = null
                        if (conversation is XMTPConversation.Group) {
                            val group = conversation.group

                            // Extract metadata and store profiles
                            try {
                                val metadata = com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.retrieveMetadata(conversation)
                                if (metadata != null) {
                                    // Extract expiresAt if present
                                    if (metadata.hasExpiresAtUnix() && metadata.expiresAtUnix > 0) {
                                        expiresAt = metadata.expiresAtUnix * 1000
                                    }

                                    // Store member profiles to database
                                    val profiles = com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.extractProfiles(metadata)
                                    val profileEntities = profiles.map { (inboxId, profile) ->
                                        MemberProfileEntity(
                                            conversationId = conversation.id,
                                            inboxId = inboxId,
                                            name = profile.name.takeIf { it.isNotBlank() },
                                            avatar = profile.image.takeIf { it.isNotBlank() }
                                        )
                                    }
                                    memberProfileDao.insertAll(profileEntities)
                                    Log.d(TAG, "Stored ${profileEntities.size} member profiles for new group ${conversation.id}")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to extract metadata for new group ${conversation.id}", e)
                            }

                            // Get latest group metadata
                            @Suppress("DEPRECATION")
                            val latestName = try { group.name?.takeIf { it.isNotBlank() } } catch (e: Exception) { null }
                            @Suppress("DEPRECATION")
                            val latestDescription = try { group.description } catch (e: Exception) { null }
                            @Suppress("DEPRECATION")
                            val latestImageUrl = try { group.imageUrl?.takeIf { it.isNotEmpty() } } catch (e: Exception) { null }

                            entity = entity.copy(
                                name = latestName ?: entity.name,
                                description = latestDescription ?: entity.description,
                                imageUrl = latestImageUrl ?: entity.imageUrl,
                                expiresAt = expiresAt
                            )

                            Log.d(TAG, "New group via stream - name='${entity.name}', description='${entity.description?.take(50)}', expiresAt=$expiresAt")

                            // Check if this group matches a pending invite
                            val matched = inviteJoinRequestsManager.checkGroupForPendingInvite(
                                groupId = conversation.id,
                                client = client
                            )

                            if (matched) {
                                Log.d(TAG, "Group ${conversation.id} matched a pending invite - setting consent to ALLOWED")
                                entity = entity.copy(consent = "allowed")
                                conversationDao.insert(entity)

                                // Ensure the consent is also updated in the database
                                updateConsent(conversation.id, ConsentState.ALLOWED)

                                // Do a full sync to ensure all data is up to date
                                syncConversations(inboxId)
                            } else {
                                Log.d(TAG, "Group ${conversation.id} did NOT match any pending invite - consent set to DENIED")
                                entity = entity.copy(consent = "denied")
                                conversationDao.insert(entity)
                            }
                        } else if (conversation is XMTPConversation.Dm) {
                            // For DMs, check if it's an invite-related DM
                            val dm = conversation as XMTPConversation.Dm
                            val consentState = try { dm.consentState() } catch (e: Exception) { org.xmtp.android.library.ConsentState.UNKNOWN }

                            when (consentState) {
                                org.xmtp.android.library.ConsentState.DENIED -> {
                                    // Already marked as hidden (invite-related), keep it hidden
                                    entity = entity.copy(consent = "denied")
                                    Log.d(TAG, "DM ${conversation.id} is already marked as DENIED (invite-related), keeping hidden")
                                }
                                org.xmtp.android.library.ConsentState.ALLOWED -> {
                                    // Already allowed, keep it visible
                                    entity = entity.copy(consent = "allowed")
                                    Log.d(TAG, "DM ${conversation.id} is already ALLOWED, keeping visible")
                                }
                                else -> {
                                    // New DM with UNKNOWN consent - show by default
                                    entity = entity.copy(consent = "allowed")
                                    Log.d(TAG, "New DM ${conversation.id} with UNKNOWN consent - showing by default")
                                }
                            }
                            conversationDao.insert(entity)
                        } else {
                            // Unknown conversation type, insert with default consent
                            conversationDao.insert(entity)
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start conversation streaming", e)
            }
        }
    }
}
