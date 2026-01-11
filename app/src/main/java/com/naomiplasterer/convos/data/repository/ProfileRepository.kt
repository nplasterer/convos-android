package com.naomiplasterer.convos.data.repository

import android.content.Context
import android.util.Log
import com.naomiplasterer.convos.data.local.dao.MemberProfileDao
import com.naomiplasterer.convos.data.local.entity.MemberProfileEntity
import com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper
import com.naomiplasterer.convos.data.xmtp.XMTPClientManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileRepository"
private const val MAX_DISPLAY_NAME_LENGTH = 100

sealed class ProfileUpdateResult {
    data object Success : ProfileUpdateResult()
    data class Error(val message: String) : ProfileUpdateResult()
}

@Singleton
class ProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memberProfileDao: MemberProfileDao,
    private val xmtpClientManager: XMTPClientManager
) {

    suspend fun updateDisplayName(
        conversationId: String,
        inboxId: String,
        name: String
    ): ProfileUpdateResult {
        return try {
            Log.d(TAG, "Updating display name for conversation: $conversationId")

            val trimmedName = name.trim()
                .take(MAX_DISPLAY_NAME_LENGTH)
                .ifEmpty { null }

            Log.d(TAG, "Trimmed name: $trimmedName")

            val existingProfile = memberProfileDao.getProfile(conversationId, inboxId)
            val updatedProfile = if (existingProfile != null) {
                existingProfile.copy(name = trimmedName)
            } else {
                MemberProfileEntity(
                    conversationId = conversationId,
                    inboxId = inboxId,
                    name = trimmedName,
                    avatar = null
                )
            }

            memberProfileDao.insert(updatedProfile)
            Log.d(TAG, "Updated profile in database")

            updateGroupMetadata(conversationId, inboxId, updatedProfile)

            ProfileUpdateResult.Success

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update display name", e)
            ProfileUpdateResult.Error(e.message ?: "Failed to update display name")
        }
    }

    suspend fun applyQuicknameToConversation(
        conversationId: String,
        inboxId: String,
        displayName: String?
    ): ProfileUpdateResult {
        return try {
            Log.d(TAG, "Applying Quickname to conversation: $conversationId")

            if (displayName.isNullOrBlank()) {
                return ProfileUpdateResult.Error("Display name cannot be empty")
            }

            val result = updateDisplayName(conversationId, inboxId, displayName)

            if (result is ProfileUpdateResult.Success) {
                Log.d(TAG, "Successfully applied Quickname to conversation")
            }

            result

        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply Quickname to conversation", e)
            ProfileUpdateResult.Error(e.message ?: "Failed to apply Quickname")
        }
    }

    private suspend fun updateGroupMetadata(
        conversationId: String,
        inboxId: String,
        profile: MemberProfileEntity
    ) {
        try {
            val client = xmtpClientManager.getClient(inboxId)

            if (client == null) {
                Log.w(TAG, "No XMTP client found for inbox: $inboxId")
                return
            }

            // Get the conversation as Conversation.Group type (needed for metadata helper)
            val conversations = client.conversations.list()
            val conversation = conversations.find { it.id == conversationId }
            if (conversation !is org.xmtp.android.library.Conversation.Group) {
                Log.w(TAG, "Conversation not found or not a group: $conversationId")
                return
            }

            val currentMetadata = ConversationMetadataHelper.retrieveMetadata(conversation)
            if (currentMetadata == null) {
                Log.w(TAG, "No metadata found for group: $conversationId")

                val newProfile = ConversationMetadataHelper.createProfile(
                    inboxId = profile.inboxId,
                    name = profile.name,
                    image = profile.avatar
                )
                val metadata = ConversationMetadataHelper.generateMetadata(
                    profiles = listOf(newProfile)
                )
                ConversationMetadataHelper.storeMetadata(conversation, metadata)
                Log.d(TAG, "Created new metadata with profile")
                return
            }

            val updatedProfile = ConversationMetadataHelper.createProfile(
                inboxId = profile.inboxId,
                name = profile.name,
                image = profile.avatar
            )

            val updatedMetadata = currentMetadata.toBuilder()
                .apply {
                    val existingIndex = profilesList.indexOfFirst { p ->
                        val existingInboxId = p.inboxId.toByteArray()
                            .joinToString("") { "%02x".format(it) }
                        existingInboxId == profile.inboxId
                    }

                    if (existingIndex >= 0) {
                        setProfiles(existingIndex, updatedProfile)
                        Log.d(TAG, "Updated existing profile in metadata")
                    } else {
                        addProfiles(updatedProfile)
                        Log.d(TAG, "Added new profile to metadata")
                    }
                }
                .build()

            ConversationMetadataHelper.storeMetadata(conversation, updatedMetadata)
            Log.d(TAG, "Successfully updated group metadata")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update group metadata", e)
        }
    }
}
