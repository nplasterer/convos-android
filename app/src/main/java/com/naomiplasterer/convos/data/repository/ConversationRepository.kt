package com.naomiplasterer.convos.data.repository

import com.naomiplasterer.convos.data.invite.InviteJoinRequestsManager
import com.naomiplasterer.convos.data.local.dao.ConversationDao
import com.naomiplasterer.convos.data.local.dao.InboxDao
import com.naomiplasterer.convos.data.local.dao.MemberProfileDao
import com.naomiplasterer.convos.data.local.dao.MessageDao
import com.naomiplasterer.convos.data.local.entity.MemberProfileEntity
import com.naomiplasterer.convos.data.mapper.toDomain
import com.naomiplasterer.convos.data.mapper.toEntity
import com.naomiplasterer.convos.data.xmtp.XMTPClientManager
import com.naomiplasterer.convos.domain.model.Conversation
import com.naomiplasterer.convos.domain.model.ConsentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    private val memberProfileDao: MemberProfileDao,
    private val messageDao: MessageDao,
    private val xmtpClientManager: XMTPClientManager,
    private val inviteJoinRequestsManager: InviteJoinRequestsManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getConversations(inboxId: String): Flow<List<Conversation>> {
        return conversationDao.getAllowedConversations(inboxId)
            .map { conversationsWithMessages ->
                Log.d(
                    TAG,
                    "getConversations: Found ${conversationsWithMessages.size} allowed conversations for inbox: $inboxId"
                )
                conversationsWithMessages.forEach { cwm ->
                    Log.d(
                        TAG,
                        "  - Conversation ${cwm.id}: consent=${cwm.consent}, isDraft=${cwm.isDraft}, name=${cwm.name}"
                    )
                }
                conversationsWithMessages.map { cwm ->
                    cwm.toConversationEntity().toDomain(
                        lastMessagePreview = cwm.lastMessagePreview
                    )
                }
            }
    }

    fun getConversation(conversationId: String): Flow<Conversation?> {
        return conversationDao.getConversation(conversationId)
            .map { it?.toDomain() }
    }

    suspend fun findConversationByTag(tag: String): Conversation? {
        return try {
            val entity = conversationDao.findConversationByTag(tag)
            val result = entity?.toDomain()
            Log.d(
                TAG,
                "findConversationByTag: tag='$tag', found=${result != null}, conversationId=${result?.id}"
            )
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error finding conversation by tag: $tag", e)
            null
        }
    }

    /**
     * Observe the database for a conversation with the given invite tag.
     * Returns a Flow that emits the conversation ID when it appears (matches iOS ValueObservation).
     * This is event-driven - automatically emits when the conversation is added to the database.
     */
    fun observeConversationByTag(tag: String): Flow<String?> {
        Log.d(TAG, "Starting reactive observation for conversation with tag: $tag")
        return conversationDao.observeConversationByTag(tag)
    }

    fun getConversationsFromAllInboxes(inboxIds: List<String>): Flow<List<Conversation>> {
        return conversationDao.getAllowedConversationsFromAllInboxes(inboxIds)
            .map { conversationsWithMessages ->
                Log.d(
                    TAG,
                    "getConversationsFromAllInboxes: Found ${conversationsWithMessages.size} allowed conversations from ${inboxIds.size} inboxes"
                )

                // Log the messages to debug
                conversationsWithMessages.forEach { cwm ->
                    Log.d(TAG, "Conversation ${cwm.name}: lastMessage=${cwm.lastMessagePreview?.take(20)}, lastMessageAt=${cwm.lastMessageAt}")
                }

                conversationsWithMessages.map { cwm ->
                    cwm.toConversationEntity().toDomain(
                        lastMessagePreview = cwm.lastMessagePreview
                    )
                }
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

            // Process any incoming join requests (creator-side invite processing)
            try {
                Log.d(TAG, "Processing join requests for inbox: $inboxId")
                val joinRequests = inviteJoinRequestsManager.processJoinRequests(client)
                if (joinRequests.isNotEmpty()) {
                    Log.d(TAG, "Processed ${joinRequests.size} join requests for inbox: $inboxId")
                    joinRequests.forEach { request ->
                        Log.d(
                            TAG,
                            "  - Added user to conversation: ${request.conversationId}, name: ${request.conversationName}"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process join requests for inbox: $inboxId", e)
                // Don't fail the entire sync if join request processing fails
            }

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
                        val metadata =
                            com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.retrieveMetadata(
                                conversation
                            )
                        if (metadata != null) {
                            Log.d(
                                TAG,
                                "Extracted metadata from group ${conversation.id}: tag=${metadata.tag}, profiles=${metadata.profilesCount}"
                            )

                            // Extract expiresAt if present
                            if (metadata.hasExpiresAtUnix() && metadata.expiresAtUnix > 0) {
                                expiresAt = metadata.expiresAtUnix * 1000 // Convert to milliseconds
                            }

                            // Store member profiles to database
                            val profiles =
                                com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.extractProfiles(
                                    metadata
                                )
                            val profileEntities = profiles.map { (inboxId, profile) ->
                                MemberProfileEntity(
                                    conversationId = conversation.id,
                                    inboxId = inboxId,
                                    name = profile.name.takeIf { it.isNotBlank() },
                                    avatar = profile.image.takeIf { it.isNotBlank() }
                                )
                            }
                            memberProfileDao.insertAll(profileEntities)
                            Log.d(
                                TAG,
                                "Stored ${profileEntities.size} member profiles to database for conversation ${conversation.id}"
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to extract metadata from group ${conversation.id}", e)
                    }

                    // Always get the latest group name/description/imageUrl from XMTP
                    // These might have been updated by other members
                    val group = conversation.group
                    val latestName = try {
                        group.name().takeIf { it.isNotBlank() }
                    } catch (e: Exception) {
                        null
                    }
                    val latestDescription = try {
                        group.description()
                    } catch (e: Exception) {
                        null
                    }
                    val latestImageUrl = try {
                        group.imageUrl().takeIf { it.isNotEmpty() }
                    } catch (e: Exception) {
                        null
                    }

                    // Update entity with latest metadata
                    entity = entity.copy(
                        name = latestName ?: entity.name,
                        description = latestDescription ?: entity.description,
                        imageUrl = latestImageUrl ?: entity.imageUrl,
                        expiresAt = expiresAt
                    )

                    Log.d(
                        TAG,
                        "Group ${conversation.id} metadata: name='${entity.name}', description='${
                            entity.description?.take(50)
                        }', imageUrl='${entity.imageUrl}', expiresAt=$expiresAt"
                    )
                    Log.d(TAG, "  [EXPIRATION DEBUG] After setting from metadata: entity.expiresAt=${entity.expiresAt}")
                }

                // Skip expired conversations - don't insert/update them in the database
                val currentTime = System.currentTimeMillis()
                if (expiresAt != null && expiresAt <= currentTime) {
                    Log.d(
                        TAG,
                        "🔥 SKIPPING EXPIRED CONVERSATION: ${conversation.id.take(8)}, expiresAt=$expiresAt, expired ${currentTime - expiresAt}ms ago"
                    )
                    // If it exists in database, delete it
                    if (existing != null) {
                        conversationDao.deleteConversation(conversation.id)
                        messageDao.deleteAllForConversation(conversation.id)
                        Log.d(TAG, "  Deleted expired conversation from database")
                    }
                    continue // Skip to next conversation
                }

                // Don't manually fetch and store last message during sync
                // The message stream already handles inserting all messages in real-time
                // The database query will automatically find the most recent message

                if (existing != null) {
                    // Conversation exists - update metadata but preserve consent and user preferences
                    Log.d(TAG, "  [EXPIRATION DEBUG] Existing conversation found: existing.expiresAt=${existing.expiresAt}")
                    var finalConsent = existing.consent

                    // NEVER change consent from ALLOWED or DENIED (both are permanent once set)
                    if (existing.consent == "allowed") {
                        Log.d(
                            TAG,
                            "Preserving consent=allowed for conversation ${conversation.id}"
                        )
                        finalConsent = "allowed"
                    } else if (existing.consent == "denied") {
                        Log.d(
                            TAG,
                            "Preserving consent=denied for conversation ${conversation.id} (user explicitly denied/deleted)"
                        )
                        finalConsent = "denied"
                    } else if (conversation is org.xmtp.android.library.Conversation.Group) {
                        // Check if this conversation has pending invite and should be upgraded to ALLOWED
                        // Use read-only check to avoid removing pending invite (stream needs it too)
                        val hasPending = inviteJoinRequestsManager.hasPendingInvite(
                            groupId = conversation.id,
                            client = client
                        )
                        if (hasPending) {
                            Log.d(
                                TAG,
                                "Existing conversation ${conversation.id} has pending invite - updating consent to ALLOWED"
                            )
                            finalConsent = "allowed"
                        }
                    }

                    entity = entity.copy(
                        consent = finalConsent, // Use updated consent if matched to pending invite
                        isPinned = existing.isPinned, // Preserve pinned state
                        isMuted = existing.isMuted, // Preserve muted state
                        isUnread = existing.isUnread, // Preserve unread state
                        lastMessageAt = existing.lastMessageAt // Preserve existing timestamp (stream updates it)
                    )
                    Log.d(TAG, "  [EXPIRATION DEBUG] Before insert: entity.expiresAt=${entity.expiresAt}")

                    // Only update the database if something meaningful changed
                    // This prevents unnecessary Room Flow emissions that cause message previews to disappear
                    val hasChanges = existing.name != entity.name ||
                            existing.description != entity.description ||
                            existing.imageUrl != entity.imageUrl ||
                            existing.consent != entity.consent ||
                            existing.expiresAt != entity.expiresAt

                    if (hasChanges) {
                        conversationDao.insert(entity)
                        Log.d(
                            TAG,
                            "Updated existing conversation with changes: ${conversation.id}, consent=$finalConsent, name='${entity.name}'"
                        )
                    } else {
                        Log.d(
                            TAG,
                            "Skipped update for conversation ${conversation.id} - no meaningful changes detected"
                        )
                    }

                } else {
                    // New conversation - check XMTP consent state
                    val consentState =
                        if (conversation is org.xmtp.android.library.Conversation.Dm) {
                            // For DMs, check the XMTP consent state
                            try {
                                conversation.consentState()
                            } catch (e: Exception) {
                                Log.w(
                                    TAG,
                                    "Failed to get consent state for DM ${conversation.id}",
                                    e
                                )
                                org.xmtp.android.library.ConsentState.UNKNOWN
                            }
                        } else {
                            // For groups, default to ALLOWED
                            org.xmtp.android.library.ConsentState.ALLOWED
                        }

                    var consent = when (consentState) {
                        org.xmtp.android.library.ConsentState.ALLOWED -> "allowed"
                        org.xmtp.android.library.ConsentState.DENIED -> "denied"
                        org.xmtp.android.library.ConsentState.UNKNOWN -> "allowed" // Default to allowed for unknown
                    }

                    // Check if this new group matches a pending invite
                    // Use read-only check to avoid removing pending invite (stream needs it too)
                    if (conversation is org.xmtp.android.library.Conversation.Group) {
                        val hasPending = inviteJoinRequestsManager.hasPendingInvite(
                            groupId = conversation.id,
                            client = client
                        )
                        if (hasPending) {
                            Log.d(
                                TAG,
                                "New conversation ${conversation.id} has pending invite - setting consent to ALLOWED"
                            )
                            consent = "allowed"
                        }
                    }

                    entity = entity.copy(consent = consent)
                    conversationDao.insert(entity)
                    Log.d(
                        TAG,
                        "New conversation synced: ${conversation.id}, consent=$consent (XMTP consent: $consentState), name='${entity.name}'"
                    )
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

    suspend fun updateUnread(conversationId: String, isUnread: Boolean) {
        try {
            conversationDao.updateUnread(conversationId, isUnread)
            Log.d(TAG, "Updated unread state for conversation: $conversationId to $isUnread")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update unread state", e)
        }
    }

    suspend fun updateExpirationTime(conversationId: String, expiresAt: Long) {
        try {
            Log.d(TAG, "[EXPIRATION DEBUG] Calling DAO to update expiration: conversationId=$conversationId, expiresAt=$expiresAt")
            conversationDao.updateExpirationTime(conversationId, expiresAt)
            Log.d(TAG, "Updated expiration time for conversation: $conversationId to $expiresAt")

            // Verify the update was successful
            val updated = conversationDao.getConversationSync(conversationId)
            Log.d(TAG, "[EXPIRATION DEBUG] After DAO update, conversation expiresAt=${updated?.expiresAt}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update expiration time", e)
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
                                val metadata =
                                    com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.retrieveMetadata(
                                        conversation
                                    )
                                if (metadata != null) {
                                    // Extract expiresAt if present
                                    if (metadata.hasExpiresAtUnix() && metadata.expiresAtUnix > 0) {
                                        expiresAt = metadata.expiresAtUnix * 1000
                                    }

                                    // Store member profiles to database
                                    val profiles =
                                        com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.extractProfiles(
                                            metadata
                                        )
                                    val profileEntities = profiles.map { (inboxId, profile) ->
                                        MemberProfileEntity(
                                            conversationId = conversation.id,
                                            inboxId = inboxId,
                                            name = profile.name.takeIf { it.isNotBlank() },
                                            avatar = profile.image.takeIf { it.isNotBlank() }
                                        )
                                    }
                                    memberProfileDao.insertAll(profileEntities)
                                    Log.d(
                                        TAG,
                                        "Stored ${profileEntities.size} member profiles for new group ${conversation.id}"
                                    )
                                }
                            } catch (e: Exception) {
                                Log.w(
                                    TAG,
                                    "Failed to extract metadata for new group ${conversation.id}",
                                    e
                                )
                            }

                            // Get latest group metadata
                            val latestName = try {
                                group.name().takeIf { it.isNotBlank() }
                            } catch (e: Exception) {
                                null
                            }
                            val latestDescription = try {
                                group.description()
                            } catch (e: Exception) {
                                null
                            }
                            val latestImageUrl = try {
                                group.imageUrl().takeIf { it.isNotEmpty() }
                            } catch (e: Exception) {
                                null
                            }

                            entity = entity.copy(
                                name = latestName ?: entity.name,
                                description = latestDescription ?: entity.description,
                                imageUrl = latestImageUrl ?: entity.imageUrl,
                                expiresAt = expiresAt
                            )

                            Log.d(
                                TAG,
                                "New group via stream - id=${conversation.id}, name='${entity.name}', inviteTag='${entity.inviteTag}', expiresAt=$expiresAt"
                            )
                            Log.d(TAG, "  [EXPIRATION DEBUG] Stream: entity.expiresAt=${entity.expiresAt}")

                            // Check if this conversation already exists in database
                            val existingConsent = conversationDao.getConversationSync(conversation.id)?.consent

                            // Never downgrade from ALLOWED to DENIED
                            if (existingConsent == "allowed") {
                                Log.d(
                                    TAG,
                                    "Group ${conversation.id} already has consent=allowed, preserving it"
                                )
                                entity = entity.copy(consent = "allowed")
                                conversationDao.insert(entity)
                            } else {
                                // Check if this group matches a pending invite
                                val matched = inviteJoinRequestsManager.checkGroupForPendingInvite(
                                    groupId = conversation.id,
                                    client = client
                                )

                                if (matched) {
                                    Log.d(
                                        TAG,
                                        "Group ${conversation.id} matched a pending invite - setting consent to ALLOWED"
                                    )
                                    entity = entity.copy(consent = "allowed")
                                    conversationDao.insert(entity)

                                    // Ensure the consent is also updated in the database
                                    updateConsent(conversation.id, ConsentState.ALLOWED)

                                    // Don't do a full sync here - it causes all conversations to flicker
                                    // The individual conversation is already inserted above
                                } else {
                                    Log.d(
                                        TAG,
                                        "Group ${conversation.id} did NOT match any pending invite - consent set to DENIED"
                                    )
                                    entity = entity.copy(consent = "denied")
                                    conversationDao.insert(entity)
                                }
                            }
                        } else if (conversation is XMTPConversation.Dm) {
                            // For DMs, check if it's an invite-related DM
                            val dm = conversation
                            val consentState = try {
                                dm.consentState()
                            } catch (e: Exception) {
                                org.xmtp.android.library.ConsentState.UNKNOWN
                            }

                            when (consentState) {
                                org.xmtp.android.library.ConsentState.DENIED -> {
                                    // Already marked as hidden (invite-related), keep it hidden
                                    entity = entity.copy(consent = "denied")
                                    Log.d(
                                        TAG,
                                        "DM ${conversation.id} is already marked as DENIED (invite-related), keeping hidden"
                                    )
                                }

                                org.xmtp.android.library.ConsentState.ALLOWED -> {
                                    // Already allowed, keep it visible
                                    entity = entity.copy(consent = "allowed")
                                    Log.d(
                                        TAG,
                                        "DM ${conversation.id} is already ALLOWED, keeping visible"
                                    )
                                }

                                else -> {
                                    // New DM with UNKNOWN consent - show by default
                                    entity = entity.copy(consent = "allowed")
                                    Log.d(
                                        TAG,
                                        "New DM ${conversation.id} with UNKNOWN consent - showing by default"
                                    )
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

    /**
     * Start streaming all messages for an inbox to process join requests in real-time.
     * This matches iOS's message streaming approach for instant join request processing.
     */
    fun startMessageStreaming(inboxId: String) {
        scope.launch {
            try {
                val client = xmtpClientManager.getClient(inboxId)
                if (client == null) {
                    Log.e(TAG, "No client for inbox: $inboxId")
                    return@launch
                }

                Log.d(TAG, "📨 Starting message streaming for inbox: $inboxId (real-time join request processing)")

                client.conversations.streamAllMessages()
                    .catch { e ->
                        Log.e(TAG, "Error in message stream", e)
                    }
                    .collect { decodedMessage ->
                        try {
                            Log.d(TAG, "📬 Received message via stream, processing as potential join request")

                            // Process all messages through join request manager
                            // The manager will filter for DMs and validate join requests
                            inviteJoinRequestsManager.processJoinRequests(
                                client = client,
                                sinceNs = decodedMessage.sentAtNs
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing streamed message", e)
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start message streaming", e)
            }
        }
    }

    /**
     * Clean up the inbox associated with a conversation.
     * Since we use one inbox per conversation, when a conversation is deleted,
     * we should remove the inbox and client to avoid accumulating unused identities.
     */
    suspend fun cleanupInboxForConversation(conversationId: String) {
        try {
            // Get the conversation to find its inbox
            val conversation = conversationDao.getConversationSync(conversationId)
            if (conversation == null) {
                Log.w(TAG, "cleanupInboxForConversation: Conversation not found: $conversationId")
                return
            }

            val inboxId = conversation.inboxId
            Log.d(
                TAG,
                "cleanupInboxForConversation: Cleaning up inbox $inboxId for conversation $conversationId"
            )

            // Check if this inbox has any other ALLOWED conversations
            val otherConversations =
                conversationDao.getAllowedConversations(inboxId).catch { emit(emptyList()) }.first()

            if (otherConversations.isEmpty()) {
                // No other allowed conversations, safe to delete the inbox
                Log.d(
                    TAG,
                    "cleanupInboxForConversation: No other allowed conversations, deleting inbox $inboxId"
                )

                // Delete all conversations for this inbox
                conversationDao.deleteAllForInbox(inboxId)

                // Delete the inbox from database
                val inbox = inboxDao.getInbox(inboxId)
                if (inbox != null) {
                    inboxDao.delete(inbox)
                }

                // Remove the client from memory
                xmtpClientManager.removeClient(inboxId)

                Log.d(TAG, "cleanupInboxForConversation: Successfully cleaned up inbox $inboxId")
            } else {
                Log.d(
                    TAG,
                    "cleanupInboxForConversation: Inbox $inboxId still has ${otherConversations.size} allowed conversations, not deleting"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup inbox for conversation $conversationId", e)
        }
    }

    /**
     * Delete expired conversations from the database immediately.
     * This triggers the Room Flow to re-emit and update the UI.
     * Call this when you detect expired conversations.
     */
    suspend fun cleanupExpiredConversations() {
        try {
            val currentTime = System.currentTimeMillis()
            val expiredConversations = conversationDao.getExpiredConversations(currentTime)

            if (expiredConversations.isEmpty()) {
                Log.d(TAG, "cleanupExpiredConversations: No expired conversations found")
                return
            }

            Log.d(TAG, "🔥 cleanupExpiredConversations: Found ${expiredConversations.size} expired conversations to delete")

            for (conversation in expiredConversations) {
                val timeExpired = currentTime - (conversation.expiresAt ?: 0)
                Log.d(
                    TAG,
                    "  Deleting expired conversation ${conversation.id.take(8)}: " +
                    "expiresAt=${conversation.expiresAt}, expired ${timeExpired}ms ago"
                )

                // Delete the conversation from database - this triggers Room Flow to re-emit
                conversationDao.deleteConversation(conversation.id)

                // Also delete all messages for this conversation
                messageDao.deleteAllForConversation(conversation.id)
            }

            Log.d(TAG, "✅ cleanupExpiredConversations: Successfully deleted ${expiredConversations.size} expired conversations")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to cleanup expired conversations", e)
        }
    }

    /**
     * Clean up inboxes that have expired conversations.
     * Call this periodically to remove "zombie" inboxes.
     */
    suspend fun cleanupExpiredInboxes() {
        try {
            val allInboxes = inboxDao.getAllInboxesList()
            val currentTime = System.currentTimeMillis()
            // Grace period: Don't delete inboxes created in the last 30 seconds
            // This prevents race conditions where we delete an inbox while a conversation is being created
            val gracePeriodMillis = 30_000L

            Log.d(
                TAG,
                "cleanupExpiredInboxes: Checking ${allInboxes.size} inboxes for expired conversations"
            )

            for (inbox in allInboxes) {
                // Skip newly created inboxes (within grace period)
                val inboxAge = currentTime - inbox.createdAt
                if (inboxAge < gracePeriodMillis) {
                    Log.d(
                        TAG,
                        "cleanupExpiredInboxes: Skipping inbox ${inbox.inboxId} (created ${inboxAge}ms ago, within ${gracePeriodMillis}ms grace period)"
                    )
                    continue
                }

                // Get all allowed conversations for this inbox (this filters out expired ones)
                val allowedConversations =
                    conversationDao.getAllowedConversations(inbox.inboxId, currentTime)
                        .catch { emit(emptyList()) }
                        .first()

                if (allowedConversations.isEmpty()) {
                    // This inbox has no allowed (non-expired) conversations
                    Log.d(
                        TAG,
                        "cleanupExpiredInboxes: Inbox ${inbox.inboxId} has no allowed conversations, cleaning up"
                    )

                    // Delete all conversations for this inbox
                    conversationDao.deleteAllForInbox(inbox.inboxId)

                    // Delete the inbox
                    inboxDao.delete(inbox)

                    // Remove client from memory
                    xmtpClientManager.removeClient(inbox.inboxId)

                    Log.d(TAG, "cleanupExpiredInboxes: Cleaned up expired inbox ${inbox.inboxId}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup expired inboxes", e)
        }
    }

    /**
     * Helper function to check if content looks like an invite code.
     * Invite codes are typically base64url encoded protobufs that start with specific patterns.
     */
    private fun looksLikeInviteCode(content: String): Boolean {
        val trimmed = content.trim()
        return when {
            // Contains * separators (iOS format)
            trimmed.contains("*") && trimmed.replace("*", "")
                .matches(Regex("^[A-Za-z0-9_-]+$")) -> true
            // Starts with common protobuf prefixes when base64 encoded
            trimmed.startsWith("Cn") || trimmed.startsWith("Cg") || trimmed.startsWith("Ch") -> {
                val base64Chars = trimmed.count { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
                base64Chars.toFloat() / trimmed.length > 0.95f
            }
            // Very long base64url string (likely encoded data)
            trimmed.length > 200 && trimmed.matches(Regex("^[A-Za-z0-9_-]+$")) -> true
            else -> false
        }
    }
}
