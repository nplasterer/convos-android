package com.naomiplasterer.convos.data.repository

import com.naomiplasterer.convos.data.local.dao.MessageDao
import com.naomiplasterer.convos.data.local.dao.MemberProfileDao
import com.naomiplasterer.convos.data.mapper.toDomain
import com.naomiplasterer.convos.data.mapper.toEntity
import com.naomiplasterer.convos.data.xmtp.XMTPClientManager
import com.naomiplasterer.convos.domain.model.Message
import com.naomiplasterer.convos.domain.model.MessageContent
import com.naomiplasterer.convos.domain.model.MessageStatus
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

    fun getMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessages(conversationId)
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

                Log.d(TAG, "  Message ${decodedMessage.id}: contentType=$contentType, from ${decodedMessage.senderInboxId}")

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
                        // Format group update as user-friendly message
                        val content = try {
                            val rawContent = decodedMessage.content<String>() ?: ""
                            formatGroupUpdate(rawContent)
                        } catch (e: Exception) {
                            Log.w(TAG, "  Failed to decode group update: ${e.message}")
                            "Group settings were updated"
                        }
                        "update" to content
                    }
                    else -> {
                        // Regular text message
                        val content = try {
                            val rawContent = decodedMessage.content<String>() ?: "[Empty message]"

                            // Check if this looks like an invite code (base64url encoded protobuf)
                            // Invite codes typically start with specific patterns and are base64url
                            if (isLikelyInviteCode(rawContent)) {
                                Log.d(TAG, "  Detected invite code message, hiding from conversation")
                                return@mapNotNull null // Skip invite code messages
                            }

                            rawContent
                        } catch (e: Exception) {
                            Log.w(TAG, "  Failed to decode message content: ${e.message}", e)
                            "[Unable to decode message]"
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

            // Update conversation's lastMessageAt if we have messages
            if (entities.isNotEmpty()) {
                val latestMessageTime = entities.maxOfOrNull { it.sentAt }
                if (latestMessageTime != null) {
                    conversationDao.updateLastMessageTime(conversationId, latestMessageTime)
                    Log.d(TAG, "Updated lastMessageAt for conversation $conversationId to $latestMessageTime")
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

            // Send the message via XMTP
            val messageId = conversation.send(text)

            // Sync to get the sent message from the network
            // The message will appear via the stream, but we sync to ensure it's in the DB
            syncMessages(inboxId, conversationId)

            Log.d(TAG, "Sent message: $messageId")
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

                Log.d(TAG, "Starting message streaming for conversation: $conversationId")

                // Get member profiles from database
                val memberProfiles = try {
                    memberProfileDao.getProfilesForConversation(conversationId)
                        .associate { it.inboxId to it.name }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load member profiles from database for streaming", e)
                    emptyMap()
                }

                conversation.streamMessages()
                    .catch { e ->
                        Log.e(TAG, "Error in message stream", e)
                    }
                    .collect { decodedMessage ->
                        val contentType = try {
                            decodedMessage.encodedContent.type.typeId
                        } catch (e: Exception) {
                            "text"
                        }

                        Log.d(TAG, "Received message via stream: ${decodedMessage.id}")
                        Log.d(TAG, "  - Content Type: $contentType")
                        Log.d(TAG, "  - Sender: ${decodedMessage.senderInboxId}")

                        // Skip unsupported system messages
                        val skipTypes = listOf(
                            "reaction",
                            "reply"
                        )

                        if (skipTypes.any { contentType.contains(it, ignoreCase = true) }) {
                            Log.d(TAG, "  Skipping message type: $contentType")
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
                                // Format group update as user-friendly message
                                val content = try {
                                    val rawContent = decodedMessage.content<String>() ?: ""
                                    formatGroupUpdate(rawContent)
                                } catch (e: Exception) {
                                    Log.w(TAG, "  Failed to decode group update: ${e.message}")
                                    "Group settings were updated"
                                }
                                "update" to content
                            }
                            else -> {
                                // Regular text message
                                val content = try {
                                    val rawContent = decodedMessage.content<String>() ?: "[Empty message]"

                                    // Check if this looks like an invite code (base64url encoded protobuf)
                                    if (isLikelyInviteCode(rawContent)) {
                                        Log.d(TAG, "  Detected invite code message in stream, hiding from conversation")
                                        return@collect // Skip invite code messages
                                    }

                                    rawContent
                                } catch (e: Exception) {
                                    Log.w(TAG, "  Failed to decode message content: ${e.message}", e)
                                    "[Unable to decode message]"
                                }
                                "text" to content
                            }
                        }

                        Log.d(TAG, "  - Content: ${messageContent.take(100)}")
                        Log.d(TAG, "  - Sent at: ${decodedMessage.sentAtNs}")

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

                        messageDao.insert(entity)
                        Log.d(TAG, "Message inserted into database")

                        // Update conversation's lastMessageAt
                        conversationDao.updateLastMessageTime(conversationId, entity.sentAt)
                        Log.d(TAG, "Updated lastMessageAt for conversation $conversationId to ${entity.sentAt}")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start message streaming", e)
            }
        }
    }

    /**
     * Formats group update messages from raw content to user-friendly text.
     * Handles metadata changes like name, description, image updates.
     */
    private fun formatGroupUpdate(rawContent: String): String {
        return try {
            // Check if this is iOS app_data format
            if (rawContent.contains("app_data:", ignoreCase = true)) {
                // Extract the base64url encoded data after "app_data: "
                val appDataRegex = """app_data:\s*([A-Za-z0-9_-]+)""".toRegex(RegexOption.IGNORE_CASE)
                val match = appDataRegex.find(rawContent)
                val encodedData = match?.groupValues?.getOrNull(1)

                if (encodedData != null) {
                    try {
                        // Decode the metadata
                        val metadata = com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.decodeMetadata(encodedData)
                        if (metadata != null) {
                            // We have the metadata, but iOS stores name/description at the group level, not in metadata
                            // The app_data update means the group properties changed
                            // Check what changed by looking at the raw content
                            return when {
                                rawContent.contains("name", ignoreCase = true) -> "Group name was updated"
                                rawContent.contains("description", ignoreCase = true) -> "Group description was updated"
                                rawContent.contains("image", ignoreCase = true) -> "Group photo was updated"
                                else -> "Group settings were updated"
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to decode app_data", e)
                    }
                }

                // If we couldn't decode, try to infer from the message
                return when {
                    rawContent.contains("name", ignoreCase = true) -> "Group name was updated"
                    rawContent.contains("description", ignoreCase = true) -> "Group description was updated"
                    rawContent.contains("image", ignoreCase = true) -> "Group photo was updated"
                    else -> "Group settings were updated"
                }
            }

            // Legacy format parsing
            when {
                // Check for group name update
                rawContent.contains("group_name", ignoreCase = true) ||
                rawContent.contains("name", ignoreCase = true) -> {
                    // Try to extract the new name from the content
                    val nameRegex = """(?:name[:\s=]+["']?)([^"',\}]+)""".toRegex(RegexOption.IGNORE_CASE)
                    val match = nameRegex.find(rawContent)
                    val newName = match?.groupValues?.getOrNull(1)?.trim()

                    if (!newName.isNullOrEmpty() && !newName.contains("app_data", ignoreCase = true)) {
                        "Group name was changed to \"$newName\""
                    } else {
                        "Group name was changed"
                    }
                }

                // Check for group image/photo update
                rawContent.contains("group_image", ignoreCase = true) ||
                rawContent.contains("image", ignoreCase = true) ||
                rawContent.contains("photo", ignoreCase = true) -> {
                    "Group photo was changed"
                }

                // Check for description update
                rawContent.contains("description", ignoreCase = true) -> {
                    "Group description was updated"
                }

                // Fallback for any other group update
                else -> {
                    Log.d(TAG, "Unknown group update format: $rawContent")
                    "Group settings were updated"
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
            trimmed.contains("*") && trimmed.replace("*", "").matches(Regex("^[A-Za-z0-9_-]+$")) -> true

            // Starts with common protobuf prefixes when base64 encoded
            trimmed.startsWith("Cn") || trimmed.startsWith("Cg") || trimmed.startsWith("Ch") -> {
                // Additional check: should be mostly base64url chars
                val base64Chars = trimmed.count { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
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
                        val memberNames = matches.mapNotNull { match ->
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
}
