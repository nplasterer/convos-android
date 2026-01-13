package com.naomiplasterer.convos.data.repository

import com.naomiplasterer.convos.data.local.dao.MessageDao
import com.naomiplasterer.convos.data.local.dao.MemberProfileDao
import com.naomiplasterer.convos.data.mapper.toDomain
import com.naomiplasterer.convos.data.xmtp.XMTPClientManager
import com.naomiplasterer.convos.domain.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MessageRepository"

@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: com.naomiplasterer.convos.data.local.dao.ConversationDao,
    private val memberProfileDao: MemberProfileDao,
    private val xmtpClientManager: XMTPClientManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Gets the most recent messages for a conversation.
     *
     * @param conversationId The conversation ID
     * @param limit Maximum number of recent messages to load (default: 200)
     * @return Flow of messages ordered by sentAt DESC (newest first)
     */
    fun getMessages(conversationId: String, limit: Int = 200): Flow<List<Message>> {
        return messageDao.getMessagesWithLimit(conversationId, limit)
            .map { entities -> entities.map { it.toDomain() } }
    }

    suspend fun syncMessages(inboxId: String, conversationId: String): Result<Unit> {
        return try {
            Log.d(TAG, "Syncing messages for conversation: $conversationId, inbox: $inboxId")

            val client = xmtpClientManager.getClient(inboxId)
                ?: return Result.failure(IllegalStateException("No client for inbox: $inboxId"))

            val conversation = client.conversations.findConversation(conversationId)
                ?: return Result.failure(IllegalStateException("Conversation not found: $conversationId"))

            // Get member profiles from database
            val memberProfiles = try {
                memberProfileDao.getProfilesForConversation(conversationId)
                    .associate { it.inboxId to it.name }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load member profiles from database", e)
                emptyMap()
            }

            Log.d(TAG, "Calling conversation.sync()...")
            conversation.sync()

            Log.d(TAG, "Getting messages from conversation...")
            val messages = conversation.messages()
            Log.d(TAG, "Retrieved ${messages.size} messages from XMTP")

            // Process all messages, including system messages
            val entities = messages.mapNotNull { decodedMessage ->
                val contentType = try {
                    decodedMessage.encodedContent.type.typeId
                } catch (e: Exception) {
                    "text"
                }

                Log.d(
                    TAG,
                    "  Message ${decodedMessage.id}: contentType=$contentType, from ${decodedMessage.senderInboxId}"
                )

                // Skip unsupported system messages that we don't need to show
                val skipTypes = listOf(
                    "reaction",
                    "reply"
                )

                if (skipTypes.any { contentType.contains(it, ignoreCase = true) }) {
                    Log.d(TAG, "  Skipping message type: $contentType")
                    return@mapNotNull null
                }

                // Handle membership changes and group updates specially
                val (finalContentType, messageContent) = when {
                    contentType.contains("membership_change", ignoreCase = true) ||
                            contentType.contains("memberChange", ignoreCase = true) -> {
                        // Format membership change as user-friendly message
                        val content = try {
                            val rawContent = decodedMessage.content<String>() ?: ""
                            formatMembershipChange(rawContent, memberProfiles)
                        } catch (e: Exception) {
                            Log.w(TAG, "  Failed to decode membership change: ${e.message}")
                            "A member update occurred"
                        }
                        "update" to content
                    }

                    contentType.contains("group_updated", ignoreCase = true) ||
                            contentType.contains("groupUpdated", ignoreCase = true) -> {
                        // Process profile updates but check if we should display the message
                        try {
                            // Get raw bytes from encoded content (same approach as streaming)
                            val rawBytes = decodedMessage.encodedContent.content.toByteArray()
                            processGroupUpdateProfiles(conversationId, rawBytes)

                            val rawContent = String(rawBytes, Charsets.UTF_8)
                            Log.d(TAG, "🔍 Group update raw content: ${rawContent.take(500)}")

                            // Check if this is ONLY a profile update (not a group property change)
                            // Profile updates won't have "name", "description", or "image_url" fields for the GROUP
                            // They only have "app_data" with profile information
                            val hasAppData = rawContent.contains("app_data", ignoreCase = true)
                            val hasGroupName = rawContent.contains("\"name\":", ignoreCase = false) ||
                                    rawContent.contains("group_name", ignoreCase = true)
                            val hasGroupDesc = rawContent.contains("\"description\":", ignoreCase = false) ||
                                    rawContent.contains("group_description", ignoreCase = true)
                            val hasGroupImage = rawContent.contains("\"image_url\":", ignoreCase = false) ||
                                    rawContent.contains("group_image", ignoreCase = true)

                            Log.d(TAG, "🔍 Update detection - hasAppData: $hasAppData, hasGroupName: $hasGroupName, hasGroupDesc: $hasGroupDesc, hasGroupImage: $hasGroupImage")

                            val isProfileOnlyUpdate = hasAppData && !hasGroupName && !hasGroupDesc && !hasGroupImage

                            if (isProfileOnlyUpdate) {
                                Log.d(TAG, "⏭️  SKIPPING profile-only update message from display")
                                return@mapNotNull null // Skip this message, don't display it
                            }

                            // Reload member profiles after processing update to get the latest names
                            var updatedMemberProfiles = try {
                                memberProfileDao.getProfilesForConversation(conversationId)
                                    .associate { it.inboxId to it.name }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to reload member profiles", e)
                                memberProfiles // Use the old map as fallback
                            }

                            // If we don't have the sender's profile, try loading from group metadata
                            if (!updatedMemberProfiles.containsKey(decodedMessage.senderInboxId) &&
                                conversation is org.xmtp.android.library.Conversation.Group) {
                                try {
                                    Log.d(TAG, "🔍 Sender profile not found in DB, checking group metadata...")
                                    val conversationGroup = org.xmtp.android.library.Conversation.Group(conversation.group)
                                    val metadata = com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.retrieveMetadata(conversationGroup)

                                    if (metadata != null && metadata.profilesCount > 0) {
                                        Log.d(TAG, "   Found ${metadata.profilesCount} profiles in group metadata")
                                        // Extract profiles and add to our map
                                        val metadataProfiles = metadata.profilesList.associate { profile ->
                                            val inboxIdHex = profile.inboxId.toByteArray()
                                                .joinToString("") { String.format("%02x", it) }
                                            val name = if (profile.hasName()) profile.name else null
                                            inboxIdHex to name
                                        }.filterValues { it != null } as Map<String, String>

                                        updatedMemberProfiles = updatedMemberProfiles + metadataProfiles
                                        Log.d(TAG, "   Added ${metadataProfiles.size} profiles from metadata")
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "   Failed to load profiles from group metadata", e)
                                }
                            }

                            // Debug: Log all available profiles
                            Log.d(TAG, "🔍 Available member profiles: ${updatedMemberProfiles.entries.joinToString { "(${it.key.take(8)} -> ${it.value})" }}")
                            Log.d(TAG, "🔍 Looking for senderInboxId: ${decodedMessage.senderInboxId.take(8)}...")

                            // Get the sender's name from updated member profiles
                            val senderName = updatedMemberProfiles[decodedMessage.senderInboxId] ?: "Somebody"
                            Log.d(TAG, "👤 Sender name for group update: $senderName (inboxId: ${decodedMessage.senderInboxId.take(8)})")

                            // Get the current group name to include in the message
                            val currentGroupName = if (conversation is org.xmtp.android.library.Conversation.Group) {
                                try {
                                    val name = conversation.group.name(); Log.d(TAG, "   Got group name: $name"); name
                                } catch (e: Exception) {
                                    Log.w(TAG, "   Failed to get group name: ${e.message}")
                                    null
                                }
                            } else {
                                Log.d(TAG, "   Not a group conversation")
                                null
                            }

                            val content = formatGroupUpdate(rawContent, senderName, currentGroupName)
                            Log.d(TAG, "   Formatted update message: $content")
                            "update" to content
                        } catch (e: Exception) {
                            Log.e(TAG, "  Failed to decode group update: ${e.message}", e)
                            "update" to "Group settings were updated"
                        }
                    }

                    else -> {
                        // Regular text message or custom content type
                        val content = try {
                            val rawContent = decodedMessage.content<String>() ?: "[Empty message]"

                            // Check if this looks like an invite code (base64url encoded protobuf)
                            // Invite codes typically start with specific patterns and are base64url
                            if (isLikelyInviteCode(rawContent)) {
                                Log.d(
                                    TAG,
                                    "  Detected invite code message, hiding from conversation"
                                )
                                return@mapNotNull null // Skip invite code messages
                            }

                            rawContent
                        } catch (e: Exception) {
                            Log.w(TAG, "  Failed to decode message content: ${e.message}, hiding from UI", e)
                            // Hide undecoded messages from the UI instead of showing error text
                            return@mapNotNull null
                        }
                        "text" to content
                    }
                }

                Log.d(TAG, "  Content preview: ${messageContent.take(50)}")
                com.naomiplasterer.convos.data.local.entity.MessageEntity(
                    id = decodedMessage.id,
                    conversationId = conversationId,
                    senderInboxId = decodedMessage.senderInboxId,
                    contentType = finalContentType,
                    content = messageContent,
                    status = "sent",
                    sentAt = decodedMessage.sentAtNs / 1_000_000,
                    deliveredAt = null
                )
            }

            messageDao.insertAll(entities)

            // Update conversation's lastMessageAt based on non-update messages only (matching iOS behavior)
            val nonUpdateMessages = entities.filter { it.contentType != "update" }
            if (nonUpdateMessages.isNotEmpty()) {
                val latestMessageTime = nonUpdateMessages.maxOfOrNull { it.sentAt }
                if (latestMessageTime != null) {
                    // Get existing conversation to preserve other fields
                    val existingConversation = conversationDao.getConversationSync(conversationId)
                    if (existingConversation != null) {
                        // Update the conversation with new lastMessageAt
                        // This forces Room to emit a new value with the updated message preview
                        conversationDao.update(existingConversation.copy(
                            lastMessageAt = latestMessageTime
                        ))
                        Log.d(
                            TAG,
                            "Updated conversation $conversationId with lastMessageAt: $latestMessageTime"
                        )
                    }
                }
            } else {
                // Even if there are no non-update messages, force a refresh to ensure UI gets the latest state
                val existingConversation = conversationDao.getConversationSync(conversationId)
                if (existingConversation != null) {
                    // Touch the conversation to force Room to re-evaluate the query
                    conversationDao.update(existingConversation)
                    Log.d(TAG, "Forced conversation refresh for $conversationId to update message preview")
                }
            }

            Log.d(TAG, "Synced ${entities.size} messages for conversation: $conversationId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync messages", e)
            Result.failure(e)
        }
    }

    suspend fun sendMessage(
        inboxId: String,
        conversationId: String,
        text: String
    ): Result<String> {
        return try {
            val client = xmtpClientManager.getClient(inboxId)
                ?: return Result.failure(IllegalStateException("No client for inbox: $inboxId"))

            val conversation = client.conversations.findConversation(conversationId)
                ?: return Result.failure(IllegalStateException("Conversation not found: $conversationId"))

            // Update lastMessageAt immediately for better UX
            // This ensures the conversation moves to the top right away
            val currentTime = System.currentTimeMillis()

            // Get existing conversation to preserve other fields
            val existingConversation = conversationDao.getConversationSync(conversationId)
            if (existingConversation != null) {
                // Update the entire conversation entity
                conversationDao.update(existingConversation.copy(
                    lastMessageAt = currentTime
                ))
                Log.d(TAG, "Optimistically updated conversation $conversationId with lastMessageAt: $currentTime")
            }

            // Send the message via XMTP
            val messageId = conversation.send(text)
            Log.d(TAG, "Sent message via XMTP: $messageId")

            // The message will arrive via the stream, no need to sync
            // Syncing was causing duplicate messages

            Log.d(TAG, "Message send complete: $messageId")
            Result.success(messageId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            Result.failure(e)
        }
    }

    fun startMessageStreaming(inboxId: String, conversationId: String) {
        scope.launch {
            try {
                val client = xmtpClientManager.getClient(inboxId)
                if (client == null) {
                    Log.e(TAG, "No client for inbox: $inboxId")
                    return@launch
                }

                val conversation = client.conversations.findConversation(conversationId)
                if (conversation == null) {
                    Log.e(TAG, "Conversation not found: $conversationId")
                    return@launch
                }

                Log.d(TAG, "🚀 STARTING MESSAGE STREAMING for conversation: $conversationId, inbox: $inboxId")
                Log.d(TAG, "   Client inbox: ${client.inboxId}")

                // Get member profiles from database
                val memberProfiles = try {
                    memberProfileDao.getProfilesForConversation(conversationId)
                        .associate { it.inboxId to it.name }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load member profiles from database for streaming", e)
                    emptyMap()
                }

                Log.d(TAG, "📡 Calling conversation.streamMessages()...")
                conversation.streamMessages()
                    .catch { e ->
                        Log.e(TAG, "❌ ERROR IN MESSAGE STREAM", e)
                    }
                    .collect { decodedMessage ->
                        Log.d(TAG, "")
                        Log.d(TAG, "========================================")
                        Log.d(TAG, "📨 NEW MESSAGE RECEIVED VIA STREAM")
                        Log.d(TAG, "========================================")
                        val contentType = try {
                            decodedMessage.encodedContent.type.typeId
                        } catch (e: Exception) {
                            "text"
                        }

                        Log.d(TAG, "   Message ID: ${decodedMessage.id}")
                        Log.d(TAG, "   Content Type: $contentType")
                        Log.d(TAG, "   Full Type: ${decodedMessage.encodedContent.type}")
                        Log.d(TAG, "   Sender Inbox ID: ${decodedMessage.senderInboxId}")
                        Log.d(TAG, "   Client Inbox ID: ${client.inboxId}")
                        Log.d(TAG, "   Is from me? ${decodedMessage.senderInboxId == client.inboxId}")

                        // Skip unsupported system messages
                        val skipTypes = listOf(
                            "reaction",
                            "reply"
                        )

                        if (skipTypes.any { contentType.contains(it, ignoreCase = true) }) {
                            Log.w(TAG, "⏭️  SKIPPING MESSAGE - Unsupported type: $contentType")
                            return@collect
                        }

                        // Handle membership changes and group updates specially
                        val (finalContentType, messageContent) = when {
                            contentType.contains("membership_change", ignoreCase = true) ||
                                    contentType.contains("memberChange", ignoreCase = true) -> {
                                // Format membership change as user-friendly message
                                val content = try {
                                    val rawContent = decodedMessage.content<String>() ?: ""
                                    formatMembershipChange(rawContent, memberProfiles)
                                } catch (e: Exception) {
                                    Log.w(TAG, "  Failed to decode membership change: ${e.message}")
                                    "A member update occurred"
                                }
                                "update" to content
                            }

                            contentType.contains("group_updated", ignoreCase = true) ||
                                    contentType.contains("groupUpdated", ignoreCase = true) -> {
                                Log.d(TAG, "🔄 DETECTED GROUP_UPDATED MESSAGE!")
                                // Process profile updates but check if we should display the message
                                try {
                                    // Get the raw encoded content bytes
                                    val rawBytes =
                                        decodedMessage.encodedContent.content.toByteArray()
                                    Log.d(TAG, "   Processing group update profiles...")
                                    processGroupUpdateProfiles(conversationId, rawBytes)

                                    val rawContent = String(rawBytes, Charsets.UTF_8)
                                    Log.d(TAG, "🔍 Group update raw content: ${rawContent.take(500)}")

                                    // Check if this is ONLY a profile update (not a group property change)
                                    // Profile updates won't have "name", "description", or "image_url" fields for the GROUP
                                    // They only have "app_data" with profile information
                                    val hasAppData = rawContent.contains("app_data", ignoreCase = true)
                                    val hasGroupName = rawContent.contains("\"name\":", ignoreCase = false) ||
                                            rawContent.contains("group_name", ignoreCase = true)
                                    val hasGroupDesc = rawContent.contains("\"description\":", ignoreCase = false) ||
                                            rawContent.contains("group_description", ignoreCase = true)
                                    val hasGroupImage = rawContent.contains("\"image_url\":", ignoreCase = false) ||
                                            rawContent.contains("group_image", ignoreCase = true)

                                    Log.d(TAG, "🔍 Update detection - hasAppData: $hasAppData, hasGroupName: $hasGroupName, hasGroupDesc: $hasGroupDesc, hasGroupImage: $hasGroupImage")

                                    val isProfileOnlyUpdate = hasAppData && !hasGroupName && !hasGroupDesc && !hasGroupImage

                                    if (isProfileOnlyUpdate) {
                                        Log.d(TAG, "⏭️  SKIPPING profile-only update message from display")
                                        return@collect // Skip this message, don't display it
                                    }

                                    // Reload member profiles after processing update to get the latest names
                                    var updatedMemberProfiles = try {
                                        memberProfileDao.getProfilesForConversation(conversationId)
                                            .associate { it.inboxId to it.name }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to reload member profiles", e)
                                        memberProfiles // Use the old map as fallback
                                    }

                                    // If we don't have the sender's profile, try loading from group metadata
                                    if (!updatedMemberProfiles.containsKey(decodedMessage.senderInboxId) &&
                                        conversation is org.xmtp.android.library.Conversation.Group) {
                                        try {
                                            Log.d(TAG, "🔍 Sender profile not found in DB, checking group metadata...")
                                            val conversationGroup = org.xmtp.android.library.Conversation.Group(conversation.group)
                                            val metadata = com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.retrieveMetadata(conversationGroup)

                                            if (metadata != null && metadata.profilesCount > 0) {
                                                Log.d(TAG, "   Found ${metadata.profilesCount} profiles in group metadata")
                                                // Extract profiles and add to our map
                                                val metadataProfiles = metadata.profilesList.associate { profile ->
                                                    val inboxIdHex = profile.inboxId.toByteArray()
                                                        .joinToString("") { String.format("%02x", it) }
                                                    val name = if (profile.hasName()) profile.name else null
                                                    inboxIdHex to name
                                                }.filterValues { it != null } as Map<String, String>

                                                updatedMemberProfiles = updatedMemberProfiles + metadataProfiles
                                                Log.d(TAG, "   Added ${metadataProfiles.size} profiles from metadata")
                                            }
                                        } catch (e: Exception) {
                                            Log.w(TAG, "   Failed to load profiles from group metadata", e)
                                        }
                                    }

                                    // Debug: Log all available profiles
                                    Log.d(TAG, "🔍 Available member profiles: ${updatedMemberProfiles.entries.joinToString { "(${it.key.take(8)} -> ${it.value})" }}")
                                    Log.d(TAG, "🔍 Looking for senderInboxId: ${decodedMessage.senderInboxId.take(8)}...")

                                    val senderName = updatedMemberProfiles[decodedMessage.senderInboxId] ?: "Somebody"
                                    Log.d(TAG, "👤 Sender name for group update: $senderName (inboxId: ${decodedMessage.senderInboxId.take(8)})")

                                    // Get the current group name to include in the message
                                    val currentGroupName = if (conversation is org.xmtp.android.library.Conversation.Group) {
                                        try {
                                            val name = conversation.group.name(); Log.d(TAG, "   Got group name: $name"); name
                                        } catch (e: Exception) {
                                            Log.w(TAG, "   Failed to get group name: ${e.message}")
                                            null
                                        }
                                    } else {
                                        Log.d(TAG, "   Not a group conversation")
                                        null
                                    }

                                    val content = formatGroupUpdate(rawContent, senderName, currentGroupName)
                                    Log.d(TAG, "   Formatted update message: $content")
                                    "update" to content
                                } catch (e: Exception) {
                                    Log.w(TAG, "  Failed to decode group update: ${e.message}", e)
                                    "update" to "Group settings were updated"
                                }
                            }

                            else -> {
                                // Regular text message or custom content type
                                val content = try {
                                    val rawContent =
                                        decodedMessage.content<String>() ?: "[Empty message]"

                                    // Check if this looks like an invite code (base64url encoded protobuf)
                                    if (isLikelyInviteCode(rawContent)) {
                                        Log.w(
                                            TAG,
                                            "⏭️  SKIPPING MESSAGE - Detected invite code, hiding from conversation"
                                        )
                                        Log.d(TAG, "   Content preview: ${rawContent.take(50)}")
                                        return@collect // Skip invite code messages
                                    }

                                    rawContent
                                } catch (e: Exception) {
                                    Log.w(
                                        TAG,
                                        "⏭️  SKIPPING MESSAGE - Failed to decode message content: ${e.message}, hiding from UI",
                                        e
                                    )
                                    // Hide undecoded messages from the UI instead of showing error text
                                    return@collect
                                }
                                "text" to content
                            }
                        }

                        Log.d(TAG, "   Content preview: ${messageContent.take(100)}")
                        Log.d(TAG, "   Sent at (ns): ${decodedMessage.sentAtNs}")

                        val entity = com.naomiplasterer.convos.data.local.entity.MessageEntity(
                            id = decodedMessage.id,
                            conversationId = conversationId,
                            senderInboxId = decodedMessage.senderInboxId,
                            contentType = finalContentType,
                            content = messageContent,
                            status = "sent",
                            sentAt = decodedMessage.sentAtNs / 1_000_000,
                            deliveredAt = null
                        )

                        Log.d(TAG, "💾 Inserting message into database...")
                        messageDao.insert(entity)
                        Log.d(TAG, "✅ MESSAGE SUCCESSFULLY INSERTED INTO DATABASE")
                        Log.d(TAG, "   ID: ${entity.id}")
                        Log.d(TAG, "   Sender: ${entity.senderInboxId}")
                        Log.d(TAG, "   Type: ${entity.contentType}")

                        // Only update lastMessageAt for non-update messages (matching iOS behavior)
                        if (entity.contentType != "update") {
                            // Get existing conversation to preserve other fields
                            val existingConversation = conversationDao.getConversationSync(conversationId)
                            if (existingConversation != null) {
                                // Update the entire conversation entity with new lastMessageAt
                                // This ensures Room's Flow emits a new value with the updated message preview
                                conversationDao.update(existingConversation.copy(
                                    lastMessageAt = entity.sentAt
                                ))
                                Log.d(
                                    TAG,
                                    "📅 Updated conversation $conversationId with lastMessageAt: ${entity.sentAt} for real-time UI update"
                                )
                            }
                        } else {
                            Log.d(TAG, "⏭️ Skipping lastMessageAt update for 'update' message")
                        }
                        Log.d(TAG, "========================================")
                        Log.d(TAG, "")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start message streaming", e)
            }
        }
    }

    /**
     * Processes group update messages to extract and save member profile updates and group metadata.
     * iOS sends updates via group_updated messages with app_data containing ConversationCustomMetadata protobuf.
     * Also checks for group name/description/image updates from the raw XMTP group object.
     */
    private suspend fun processGroupUpdateProfiles(conversationId: String, rawBytes: ByteArray) {
        try {
            Log.d(TAG, "Processing group update for profiles. Raw bytes length: ${rawBytes.size}")

            // Find "app_data" bytes in the content
            // Note: iOS sends BOTH old and new profile data, so we need the LAST occurrence
            val appDataBytes = "app_data".toByteArray(Charsets.UTF_8)
            val appDataIndex = rawBytes.lastIndexOfSubArray(appDataBytes)

            val encodedData = if (appDataIndex >= 0) {
                Log.d(TAG, "Found 'app_data' at byte index: $appDataIndex")

                // Skip past "app_data" (8 bytes)
                var startIndex = appDataIndex + 8

                // Skip the protobuf field header (usually 1-2 bytes for field number + type)
                // In protobuf: field 2, type length-delimited (wire type 2) = 0x12
                // Then the length byte(s) - protobuf uses varint encoding
                if (startIndex < rawBytes.size && rawBytes[startIndex] == 0x12.toByte()) {
                    startIndex++ // Skip field header

                    // Decode varint length
                    var length = 0
                    var shift = 0
                    var lengthBytes = 0

                    while (startIndex < rawBytes.size && lengthBytes < 5) { // Max 5 bytes for 32-bit varint
                        val byte = rawBytes[startIndex].toInt() and 0xFF
                        length = length or ((byte and 0x7F) shl shift)
                        lengthBytes++
                        startIndex++

                        if ((byte and 0x80) == 0) {
                            // High bit not set, this is the last byte
                            break
                        }
                        shift += 7
                    }

                    Log.d(
                        TAG,
                        "Protobuf field length: $length (decoded from $lengthBytes varint bytes), data starting at byte: $startIndex"
                    )

                    // Extract the base64url data (length bytes)
                    if (startIndex + length <= rawBytes.size) {
                        val dataBytes = rawBytes.copyOfRange(startIndex, startIndex + length)
                        // Convert bytes to ASCII string (base64url is ASCII-safe)
                        String(dataBytes, Charsets.UTF_8)
                    } else {
                        Log.w(TAG, "Not enough bytes for declared length: $length")
                        null
                    }
                } else {
                    Log.w(
                        TAG,
                        "Unexpected byte after app_data: ${
                            if (startIndex < rawBytes.size) "0x${
                                rawBytes[startIndex].toString(16)
                            }" else "EOF"
                        }"
                    )
                    null
                }
            } else {
                null
            }

            if (encodedData != null) {
                Log.d(TAG, "✅ Found app_data in group update!")
                Log.d(TAG, "   Encoded data length: ${encodedData.length}")
                Log.d(TAG, "   Encoded data (first 100 chars): ${encodedData.take(100)}")
                Log.d(TAG, "   Full encoded data: $encodedData")

                try {
                    Log.d(TAG, "📦 Attempting to decode protobuf metadata...")
                    val metadata =
                        com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.decodeMetadata(
                            encodedData
                        )

                    if (metadata == null) {
                        Log.e(TAG, "❌ Metadata decoded to null!")
                        return
                    }

                    Log.d(TAG, "✅ Successfully decoded metadata!")
                    Log.d(TAG, "   Tag: ${metadata.tag}")
                    Log.d(TAG, "   Profiles count: ${metadata.profilesCount}")

                    if (metadata.profilesCount == 0) {
                        Log.w(TAG, "⚠️  No profiles in metadata")
                        return
                    }

                    for ((index, profile) in metadata.profilesList.withIndex()) {
                        val inboxIdHex = profile.inboxId.toByteArray()
                            .joinToString("") { String.format("%02x", it) }
                        val name = if (profile.hasName()) profile.name else null
                        val image = if (profile.hasImage()) profile.image else null

                        Log.d(TAG, "👤 Profile #${index + 1}:")
                        Log.d(TAG, "   InboxId (hex): $inboxIdHex")
                        Log.d(TAG, "   Name: $name")
                        Log.d(TAG, "   Image: $image")

                        val profileEntity =
                            com.naomiplasterer.convos.data.local.entity.MemberProfileEntity(
                                conversationId = conversationId,
                                inboxId = inboxIdHex,
                                name = name,
                                avatar = image
                            )

                        Log.d(TAG, "💾 Inserting profile into database...")
                        memberProfileDao.insert(profileEntity)
                        Log.d(TAG, "✅ Profile saved to database")

                        // Verify it was saved
                        val savedProfile = memberProfileDao.getProfile(conversationId, inboxIdHex)
                        if (savedProfile != null) {
                            Log.d(TAG, "✅ Verified profile in database: name=${savedProfile.name}")
                        } else {
                            Log.e(TAG, "❌ Profile NOT found in database after insert!")
                        }
                    }

                    Log.d(
                        TAG,
                        "🎉 Successfully processed ${metadata.profilesCount} profiles from group update"
                    )
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Failed to decode or save profiles from app_data: ${encodedData.take(50)}...",
                        e
                    )
                }
            } else {
                Log.d(TAG, "No app_data found in group update message")
                // Log the raw bytes as hex to see what we're actually dealing with
                val hexPreview = rawBytes.take(100).joinToString("") {
                    String.format("%02X ", it)
                }
                Log.d(TAG, "Raw bytes hex preview: $hexPreview")
            }

            // Also check for group metadata changes (name, description, image) from XMTP group object
            try {
                val conversation = conversationDao.getConversationSync(conversationId)
                if (conversation != null) {
                    val client = xmtpClientManager.getClient(conversation.inboxId)
                    if (client != null) {
                        val group = client.conversations.findGroup(conversationId)
                        if (group != null) {
                            val groupName = try {
                                group.name().takeIf { it.isNotBlank() }
                            } catch (e: Exception) {
                                null
                            }

                            val groupDescription = try {
                                group.description()
                            } catch (e: Exception) {
                                null
                            }

                            val groupImageUrl = try {
                                group.imageUrl().takeIf { it.isNotEmpty() }
                            } catch (e: Exception) {
                                null
                            }

                            // Only update if there are actual changes
                            if (groupName != conversation.name ||
                                groupDescription != conversation.description ||
                                groupImageUrl != conversation.imageUrl
                            ) {
                                Log.d(
                                    TAG,
                                    "Updating conversation metadata - name: $groupName, description: $groupDescription, imageUrl: $groupImageUrl"
                                )
                                conversationDao.updateMetadata(
                                    conversationId,
                                    groupName,
                                    groupDescription,
                                    groupImageUrl
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update group metadata", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing group update profiles", e)
        }
    }

    /**
     * Formats group update messages from raw content to user-friendly text.
     * Handles metadata changes like name, description, image updates.
     */
    private fun formatGroupUpdate(rawContent: String, senderName: String, currentGroupName: String?): String {
        return try {
            // Check if this is iOS app_data format
            if (rawContent.contains("app_data:", ignoreCase = true)) {
                // Extract the base64url encoded data after "app_data: "
                val appDataRegex =
                    """app_data:\s*([A-Za-z0-9_-]+)""".toRegex(RegexOption.IGNORE_CASE)
                val match = appDataRegex.find(rawContent)
                val encodedData = match?.groupValues?.getOrNull(1)

                if (encodedData != null) {
                    try {
                        // Decode the metadata
                        val metadata =
                            com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.decodeMetadata(
                                encodedData
                            )
                        if (metadata != null) {
                            // We have the metadata, but iOS stores name/description at the group level, not in metadata
                            // The app_data update means the group properties changed
                            // Check what changed by looking at the raw content
                            return when {
                                rawContent.contains(
                                    "name",
                                    ignoreCase = true
                                ) -> {
                                    if (!currentGroupName.isNullOrBlank()) {
                                        "$senderName changed the name of the group to $currentGroupName"
                                    } else {
                                        "$senderName updated the group name"
                                    }
                                }

                                rawContent.contains(
                                    "description",
                                    ignoreCase = true
                                ) -> "$senderName updated the group description"

                                rawContent.contains(
                                    "image",
                                    ignoreCase = true
                                ) -> "$senderName updated the group photo"

                                else -> "$senderName updated the group settings"
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to decode app_data", e)
                    }
                }

                // If we couldn't decode, try to infer from the message
                return when {
                    rawContent.contains("name", ignoreCase = true) -> {
                        if (!currentGroupName.isNullOrBlank()) {
                            "$senderName changed the name of the group to $currentGroupName"
                        } else {
                            "$senderName updated the group name"
                        }
                    }
                    rawContent.contains(
                        "description",
                        ignoreCase = true
                    ) -> "$senderName updated the group description"

                    rawContent.contains("image", ignoreCase = true) -> "$senderName updated the group photo"
                    else -> "$senderName updated the group settings"
                }
            }

            // Legacy format parsing
            when {
                // Check for group name update
                rawContent.contains("group_name", ignoreCase = true) ||
                        rawContent.contains("name", ignoreCase = true) -> {
                    // Use the current group name if available, otherwise try to extract from content
                    if (!currentGroupName.isNullOrBlank()) {
                        "$senderName changed the name of the group to $currentGroupName"
                    } else {
                        // Try to extract the new name from the content
                        val nameRegex =
                            """(?:name[:\s=]+["']?)([^"',\}]+)""".toRegex(RegexOption.IGNORE_CASE)
                        val match = nameRegex.find(rawContent)
                        val newName = match?.groupValues?.getOrNull(1)?.trim()

                        if (!newName.isNullOrEmpty() && !newName.contains(
                                "app_data",
                                ignoreCase = true
                            )
                        ) {
                            "$senderName changed the name of the group to $newName"
                        } else {
                            "$senderName changed the group name"
                        }
                    }
                }

                // Check for group image/photo update
                rawContent.contains("group_image", ignoreCase = true) ||
                        rawContent.contains("image", ignoreCase = true) ||
                        rawContent.contains("photo", ignoreCase = true) -> {
                    "$senderName changed the group photo"
                }

                // Check for description update
                rawContent.contains("description", ignoreCase = true) -> {
                    "$senderName updated the group description"
                }

                // Fallback for any other group update
                else -> {
                    Log.d(TAG, "Unknown group update format: $rawContent")
                    "$senderName updated the group settings"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error formatting group update", e)
            "Group settings were updated"
        }
    }

    /**
     * Checks if a message content looks like an invite code.
     * Invite codes are typically base64url encoded protobufs that start with specific patterns.
     */
    private fun isLikelyInviteCode(content: String): Boolean {
        // Check if it looks like a base64url encoded protobuf
        // Common patterns in invite codes:
        // - Starts with "Cn" (common protobuf prefix)
        // - Contains "*" separators for iOS compatibility
        // - Is mostly base64url characters with high entropy
        val trimmed = content.trim()

        return when {
            // Contains * separators (iOS format)
            trimmed.contains("*") && trimmed.replace("*", "")
                .matches(Regex("^[A-Za-z0-9_-]+$")) -> true

            // Starts with common protobuf prefixes when base64 encoded
            trimmed.startsWith("Cn") || trimmed.startsWith("Cg") || trimmed.startsWith("Ch") -> {
                // Additional check: should be mostly base64url chars
                val base64Chars =
                    trimmed.count { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
                base64Chars.toFloat() / trimmed.length > 0.95f
            }

            // Very long base64url string (likely encoded data)
            trimmed.length > 200 && trimmed.matches(Regex("^[A-Za-z0-9_-]+$")) -> true

            else -> false
        }
    }

    /**
     * Formats membership change messages from raw content to user-friendly text.
     * Handles various formats including:
     * - MembershipChange(action: Add, members: [InboxId(...)])
     * - Multiple members added/removed
     */
    private fun formatMembershipChange(
        rawContent: String,
        memberProfiles: Map<String, String?>
    ): String {
        return try {
            when {
                // Check for "Add" action with members
                rawContent.contains("action: Add", ignoreCase = true) ||
                        rawContent.contains("action=Add", ignoreCase = true) -> {
                    // Try to extract inbox IDs and get names from profiles
                    val inboxIdPattern = """InboxId\(([^)]+)\)""".toRegex(RegexOption.IGNORE_CASE)
                    val matches = inboxIdPattern.findAll(rawContent).toList()

                    if (matches.isNotEmpty()) {
                        // Extract names from profiles
                        val memberNames = matches.map { match ->
                            val inboxId = match.groupValues.getOrNull(1)?.trim()
                            if (inboxId != null) {
                                // Get name from database profiles, fallback to "Somebody"
                                memberProfiles[inboxId]?.takeIf { it.isNotBlank() } ?: "Somebody"
                            } else {
                                "Somebody"
                            }
                        }

                        when (memberNames.size) {
                            0 -> "Members were added to the conversation"
                            1 -> "${memberNames[0]} joined by invitation"
                            2 -> "${memberNames[0]} and ${memberNames[1]} joined by invitation"
                            else -> "${memberNames.size} members joined by invitation"
                        }
                    } else {
                        // Fallback to counting members without names
                        val memberCount = "InboxId".toRegex(RegexOption.IGNORE_CASE)
                            .findAll(rawContent).count()

                        when (memberCount) {
                            0 -> "Members were added to the conversation"
                            1 -> "Somebody joined by invitation"
                            else -> "$memberCount members joined by invitation"
                        }
                    }
                }

                // Check for "Remove" action
                rawContent.contains("action: Remove", ignoreCase = true) ||
                        rawContent.contains("action=Remove", ignoreCase = true) -> {
                    val memberCount = "InboxId".toRegex(RegexOption.IGNORE_CASE)
                        .findAll(rawContent).count()

                    when (memberCount) {
                        0 -> "Members left the conversation"
                        1 -> "Somebody left the conversation"
                        else -> "$memberCount members left the conversation"
                    }
                }

                // Generic membership change
                rawContent.contains("MembershipChange", ignoreCase = true) -> {
                    "Membership was updated"
                }

                // Fallback for any other format
                else -> {
                    Log.d(TAG, "Unknown membership change format: $rawContent")
                    "Membership was updated"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error formatting membership change", e)
            "Membership was updated"
        }
    }

    /**
     * Helper extension function to find the LAST occurrence of a byte subarray
     */
    private fun ByteArray.lastIndexOfSubArray(subArray: ByteArray): Int {
        for (i in (this.size - subArray.size) downTo 0) {
            var found = true
            for (j in subArray.indices) {
                if (this[i + j] != subArray[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }
}
