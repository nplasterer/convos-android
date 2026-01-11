package com.naomiplasterer.convos.ui.conversation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naomiplasterer.convos.crypto.SignedInviteValidator
import com.naomiplasterer.convos.data.local.dao.MemberProfileDao
import com.naomiplasterer.convos.data.repository.ConversationRepository
import com.naomiplasterer.convos.data.repository.MessageRepository
import com.naomiplasterer.convos.data.session.SessionManager
import com.naomiplasterer.convos.data.xmtp.XMTPClientManager
import com.naomiplasterer.convos.domain.model.Conversation
import com.naomiplasterer.convos.domain.model.Message
import com.naomiplasterer.convos.domain.model.MessageContent
import com.naomiplasterer.convos.domain.model.meaningfullyEquals
import com.naomiplasterer.convos.proto.ConversationMetadataProtos
import com.naomiplasterer.convos.codecs.ExplodeSettingsCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.util.Log
import org.xmtp.android.library.Client
import java.util.UUID
import javax.inject.Inject

private const val TAG = "ConversationViewModel"

@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val sessionManager: SessionManager,
    private val xmtpClientManager: XMTPClientManager,
    private val memberProfileDao: MemberProfileDao
) : ViewModel() {

    private val conversationId: String = checkNotNull(savedStateHandle["conversationId"])

    private val _uiState = MutableStateFlow<ConversationUiState>(ConversationUiState.Loading)
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    private val _inviteCode = MutableStateFlow<String?>(null)
    val inviteCode: StateFlow<String?> = _inviteCode.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _isEditingInfo = MutableStateFlow(false)
    val isEditingInfo: StateFlow<Boolean> = _isEditingInfo.asStateFlow()

    private val _editingConversationName = MutableStateFlow("")
    val editingConversationName: StateFlow<String> = _editingConversationName.asStateFlow()

    private val _isSavingInfo = MutableStateFlow(false)
    val isSavingInfo: StateFlow<Boolean> = _isSavingInfo.asStateFlow()

    private val _saveInfoError = MutableStateFlow<String?>(null)
    val saveInfoError: StateFlow<String?> = _saveInfoError.asStateFlow()

    // Cache members to prevent creating new objects on every Flow emission
    private var cachedMembers: List<com.naomiplasterer.convos.domain.model.Member> = emptyList()
    private var cachedMemberCount: Int = 0
    private var lastMemberLoadTime: Long = 0

    // Cache full conversation to prevent unnecessary UI updates when only timestamps change
    private var cachedConversation: Conversation? = null

    // Buffer messages that arrive before conversation is loaded
    private var pendingMessages: List<Message> = emptyList()

    init {
        // Register the ExplodeSettings codec
        try {
            Client.register(ExplodeSettingsCodec())
        } catch (e: Exception) {
            // Codec might already be registered, that's ok
            Log.d(
                TAG,
                "ExplodeSettings codec already registered or registration failed: ${e.message}"
            )
        }
        loadConversation()
        loadMessages()
        syncAndStartStreaming()
    }

    private fun syncAndStartStreaming() {
        viewModelScope.launch {
            // Wait for conversation to load to get the correct inboxId
            val conversation = conversationRepository.getConversation(conversationId).first()
            if (conversation != null) {
                val inboxId = conversation.inboxId

                // Ensure client is loaded for this inbox
                sessionManager.ensureClientLoaded(inboxId).fold(
                    onSuccess = {
                        // First sync existing messages from the network
                        Log.d(TAG, "Syncing messages for conversation: $conversationId with inbox: $inboxId")
                        messageRepository.syncMessages(inboxId, conversationId).fold(
                            onSuccess = {
                                Log.d(TAG, "Messages synced successfully, starting streaming")
                            },
                            onFailure = { error ->
                                Log.e(TAG, "Failed to sync messages", error)
                            }
                        )

                        // Then start streaming for new messages
                        messageRepository.startMessageStreaming(inboxId, conversationId)
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to ensure client loaded for inbox: $inboxId", error)
                    }
                )
            }
        }
    }

    private fun loadConversation() {
        viewModelScope.launch {
            conversationRepository.getConversation(conversationId)
                .distinctUntilChanged() // Only update when conversation actually changes
                .collectLatest { conversation ->
                    if (conversation != null) {
                        updateUiStateWithConversation(conversation)
                    } else {
                        _uiState.value = ConversationUiState.Error("Conversation not found")
                    }
                }
        }
    }

    private fun loadMessages() {
        viewModelScope.launch {
            messageRepository.getMessages(conversationId)
                .distinctUntilChanged { old, new ->
                    // Only compare message IDs - if the IDs are the same, it's the same list of messages
                    // This prevents updates when only metadata (like deliveredAt) changes
                    val oldIds = old.map { it.id }
                    val newIds = new.map { it.id }
                    oldIds == newIds
                }
                .collectLatest { messages ->
                    Log.d(TAG, "")
                    Log.d(TAG, "========================================")
                    Log.d(TAG, "💬 MESSAGES FLOW EMITTED")
                    Log.d(TAG, "========================================")
                    Log.d(TAG, "   Total messages: ${messages.size}")
                    messages.forEachIndexed { index, message ->
                        val contentPreview = when (val content = message.content) {
                            is MessageContent.Text -> content.text.take(50)
                            is MessageContent.Emoji -> content.emoji
                            is MessageContent.Attachment -> "Attachment: ${content.url}"
                            is MessageContent.Update -> "${content.updateType}: ${content.details.take(50)}"
                        }
                        Log.d(TAG, "   Message #${index + 1}:")
                        Log.d(TAG, "     ID: ${message.id}")
                        Log.d(TAG, "     From: ${message.senderInboxId}")
                        Log.d(TAG, "     Content type: ${message.content::class.simpleName}")
                        Log.d(TAG, "     Content: $contentPreview")
                    }
                    Log.d(TAG, "========================================")
                    Log.d(TAG, "")
                    updateUiStateWithMessages(messages)
                }
        }
    }

    private fun updateUiStateWithConversation(conversation: Conversation) {
        viewModelScope.launch {
            // If incoming conversation has no members, preserve cached members for comparison
            val conversationForComparison = if (conversation.members.isEmpty() && cachedConversation != null) {
                conversation.copy(members = cachedConversation!!.members)
            } else {
                conversation
            }

            // Determine which conversation object to use in UI state
            val now = System.currentTimeMillis()
            val memberRefreshInterval = 10_000L // Reload members every 10 seconds
            val shouldRefreshMembers = (now - lastMemberLoadTime) > memberRefreshInterval

            val conversationWithMembers = if (cachedConversation != null && conversationForComparison.meaningfullyEquals(cachedConversation) && !shouldRefreshMembers) {
                // Conversation hasn't meaningfully changed and members were recently loaded
                // Reuse the EXACT same cached object to prevent unnecessary recomposition
                Log.d(TAG, "Conversation hasn't meaningfully changed, reusing cached conversation object")
                cachedConversation!! // Use the exact same reference
            } else {
                // Conversation has changed OR members need refresh - determine if we need to reload members
                val updated = if (conversationForComparison.members.isNotEmpty() &&
                                  conversationForComparison.members.size != cachedMemberCount) {
                    // Member count changed, reload
                    Log.d(TAG, "Member count changed (${cachedMemberCount} → ${conversationForComparison.members.size}), reloading members...")
                    loadConversationMembers(conversation)
                } else if (cachedMembers.isEmpty() || shouldRefreshMembers) {
                    // First load OR time to refresh
                    if (shouldRefreshMembers) {
                        Log.d(TAG, "Refreshing members (last load was ${(now - lastMemberLoadTime) / 1000}s ago)...")
                    } else {
                        Log.d(TAG, "First load, fetching members...")
                    }
                    loadConversationMembers(conversation)
                } else {
                    // Reuse cached members
                    Log.d(TAG, "Reusing cached members (count: ${cachedMembers.size})")
                    conversation.copy(members = cachedMembers)
                }

                // Cache the full conversation
                cachedConversation = updated
                updated
            }

            // Only update UI state if the conversation object is different from what's currently in the state
            // This prevents creating new UI state objects when nothing has changed
            val currentState = _uiState.value
            if (currentState is ConversationUiState.Success && currentState.conversation === conversationWithMembers) {
                // Same conversation object reference - don't update to avoid triggering recomposition
                Log.d(TAG, "UI state already has this conversation object, skipping update")
                return@launch
            }

            _uiState.value = when (currentState) {
                is ConversationUiState.Success -> currentState.copy(conversation = conversationWithMembers)
                else -> {
                    // Transitioning from Loading to Success - apply pending messages if any
                    Log.d(TAG, "Transitioning to Success state with ${pendingMessages.size} pending messages")
                    ConversationUiState.Success(
                        conversation = conversationWithMembers,
                        messages = pendingMessages
                    )
                }
            }
        }
    }

    private suspend fun loadConversationMembers(conversation: Conversation): Conversation {
        return try {
            val client = xmtpClientManager.getClient(conversation.inboxId) ?: return conversation

            val group = client.conversations.findGroup(conversation.id) ?: return conversation
            val xmtpMembers = group.members()

            // Load member profiles from database
            Log.d(TAG, "👥 Loading member profiles for conversation: ${conversation.id}")
            var profiles = memberProfileDao.getProfilesForConversation(conversation.id)
            Log.d(TAG, "   Found ${profiles.size} profiles in database")

            // If we don't have profiles for all members, try loading from group metadata
            if (profiles.size < xmtpMembers.size) {
                Log.d(TAG, "   Missing profiles (have ${profiles.size}, need ${xmtpMembers.size}), checking group metadata...")
                try {
                    val conversationGroup = org.xmtp.android.library.Conversation.Group(group)
                    val metadata = com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.retrieveMetadata(conversationGroup)

                    if (metadata != null && metadata.profilesCount > 0) {
                        Log.d(TAG, "   Found ${metadata.profilesCount} profiles in group metadata")

                        // Extract and save profiles from metadata
                        for (metadataProfile in metadata.profilesList) {
                            val inboxIdHex = metadataProfile.inboxId.toByteArray()
                                .joinToString("") { String.format("%02x", it) }
                            val name = if (metadataProfile.hasName()) metadataProfile.name else null
                            val image = if (metadataProfile.hasImage()) metadataProfile.image else null

                            Log.d(TAG, "   Profile from metadata: inboxId=${inboxIdHex.take(8)}, name=$name")

                            // Save to database
                            val profileEntity = com.naomiplasterer.convos.data.local.entity.MemberProfileEntity(
                                conversationId = conversation.id,
                                inboxId = inboxIdHex,
                                name = name,
                                avatar = image
                            )
                            memberProfileDao.insert(profileEntity)
                        }

                        // Reload profiles from database
                        profiles = memberProfileDao.getProfilesForConversation(conversation.id)
                        Log.d(TAG, "   After loading from metadata: ${profiles.size} profiles in database")
                    } else {
                        Log.d(TAG, "   No profiles found in group metadata")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "   Failed to load profiles from group metadata", e)
                }
            }

            for (profile in profiles) {
                Log.d(
                    TAG,
                    "   Profile: inboxId=${profile.inboxId.take(8)}..., name=${profile.name}"
                )
            }

            val profileMap = profiles.associateBy { it.inboxId }

            Log.d(TAG, "   XMTP members count: ${xmtpMembers.size}")
            val members = xmtpMembers.map { xmtpMember ->
                val profile = profileMap[xmtpMember.inboxId]
                Log.d(
                    TAG,
                    "   Member ${xmtpMember.inboxId.take(8)}: profile found=${profile != null}, name=${profile?.name}"
                )

                com.naomiplasterer.convos.domain.model.Member(
                    inboxId = xmtpMember.inboxId,
                    addresses = listOf(xmtpMember.inboxId.take(8)),
                    permissionLevel = com.naomiplasterer.convos.domain.model.PermissionLevel.MEMBER,
                    consentState = com.naomiplasterer.convos.domain.model.ConsentState.ALLOWED,
                    addedAt = System.currentTimeMillis(),
                    name = profile?.name,
                    imageUrl = profile?.avatar
                )
            }

            Log.d(TAG, "✅ Loaded ${members.size} members with profiles")

            // Cache the members and member count
            cachedMembers = members
            cachedMemberCount = members.size
            lastMemberLoadTime = System.currentTimeMillis()

            conversation.copy(members = members)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load conversation members", e)
            conversation
        }
    }

    private fun updateUiStateWithMessages(messages: List<Message>) {
        Log.d(TAG, "💬 updateUiStateWithMessages called with ${messages.size} messages")
        val currentState = _uiState.value
        if (currentState is ConversationUiState.Success) {
            // CRITICAL FIX: Prevent messages from disappearing due to Room Flow emitting stale/partial data
            // This happens when:
            // 1. Conversation table updates trigger messages Flow (foreign key) with 0 messages
            // 2. Message stream inserts trigger Flow with only the newly inserted message visible
            //
            // HOWEVER: We must allow updates if there are NEW messages (different IDs)
            // This happens when a message arrives during a sync operation
            val finalMessages = if (messages.size < currentState.messages.size) {
                // Check if there are any NEW message IDs that we haven't seen before
                val currentMessageIds = currentState.messages.map { it.id }.toSet()
                val newMessageIds = messages.map { it.id }.toSet()
                val hasNewMessages = newMessageIds.any { it !in currentMessageIds }

                if (hasNewMessages) {
                    // This is partial data with new messages - MERGE instead of replacing
                    // The next database emission should have all messages
                    val newMessagesToAdd = messages.filter { it.id !in currentMessageIds }
                    val merged = (currentState.messages + newMessagesToAdd).sortedByDescending { it.sentAt }
                    Log.d(TAG, "💬 MERGING partial list with current messages (had ${currentState.messages.size}, got ${messages.size} partial, merged to ${merged.size}, new IDs: ${newMessageIds - currentMessageIds})")
                    merged
                } else {
                    Log.w(TAG, "💬 IGNORING incomplete message list - Room emitted stale/partial data (had ${currentState.messages.size}, got ${messages.size} messages)")
                    return
                }
            } else {
                // Normal case: we have same or more messages than before
                messages
            }

            Log.d(TAG, "💬 Updating UI state with messages (current: ${currentState.messages.size} → new: ${finalMessages.size})")
            _uiState.value = currentState.copy(messages = finalMessages)
        } else {
            // State is still Loading - buffer messages to apply when conversation loads
            Log.d(TAG, "💬 Buffering ${messages.size} messages (UI state is ${currentState::class.simpleName})")
            pendingMessages = messages
        }
    }

    fun updateMessageText(text: String) {
        _messageText.value = text
    }

    fun sendMessage() {
        val text = _messageText.value.trim()
        if (text.isEmpty() || _isSending.value) return

        viewModelScope.launch {
            _isSending.value = true

            // Get the conversation's inboxId from current UI state
            val currentState = _uiState.value
            val inboxId = if (currentState is ConversationUiState.Success) {
                currentState.conversation.inboxId
            } else {
                null
            }

            if (inboxId == null) {
                Log.e(TAG, "Cannot send message: conversation not loaded")
                _isSending.value = false
                return@launch
            }

            messageRepository.sendMessage(
                inboxId = inboxId,
                conversationId = conversationId,
                text = text
            ).fold(
                onSuccess = {
                    Log.d(TAG, "Message sent successfully")
                    // Clear text after successful send (not immediately, to prevent keyboard dismissal)
                    _messageText.value = ""
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to send message", error)
                    // Don't clear text on failure so user can retry
                }
            )

            _isSending.value = false
        }
    }

    fun syncMessages() {
        viewModelScope.launch {
            // Get the conversation's inboxId from current UI state
            val currentState = _uiState.value
            val inboxId = if (currentState is ConversationUiState.Success) {
                currentState.conversation.inboxId
            } else {
                null
            }

            if (inboxId == null) {
                Log.e(TAG, "Cannot sync messages: conversation not loaded")
                return@launch
            }

            messageRepository.syncMessages(inboxId, conversationId).fold(
                onSuccess = {
                    Log.d(TAG, "Messages synced successfully")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to sync messages", error)
                }
            )
        }
    }

    fun markAsRead() {
        viewModelScope.launch {
            conversationRepository.updateUnread(conversationId, false)
        }
    }

    fun deleteConversation() {
        viewModelScope.launch {
            conversationRepository.deleteConversation(conversationId)
            Log.d(TAG, "Deleted conversation: $conversationId")
        }
    }

    fun generateInviteCode() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Generating invite code for conversation: $conversationId")

                // Get the conversation's inboxId from current UI state
                val currentState = _uiState.value
                val inboxId = if (currentState is ConversationUiState.Success) {
                    currentState.conversation.inboxId
                } else {
                    null
                }

                if (inboxId == null) {
                    Log.e(TAG, "Cannot generate invite: conversation not loaded")
                    return@launch
                }

                val client = xmtpClientManager.getClient(inboxId)
                if (client == null) {
                    Log.e(TAG, "Cannot generate invite: no XMTP client")
                    return@launch
                }

                // Get the group
                val group = client.conversations.findGroup(conversationId)
                if (group == null) {
                    Log.e(TAG, "Cannot generate invite: group not found")
                    return@launch
                }

                // Get conversation details from current state
                val state = _uiState.value
                val conversation =
                    if (state is ConversationUiState.Success) state.conversation else null

                // Try to retrieve existing tag from group metadata, or generate new one
                val inviteTag = try {
                    // Use ConversationMetadataHelper which reads from appData() with description fallback
                    val conversationGroup = org.xmtp.android.library.Conversation.Group(group)
                    val customMetadata =
                        com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.retrieveMetadata(
                            conversationGroup
                        )
                    if (customMetadata != null && customMetadata.tag.isNotBlank()) {
                        Log.d(TAG, "Found existing tag in group metadata: ${customMetadata.tag}")
                        customMetadata.tag
                    } else {
                        throw IllegalStateException("Tag is empty or metadata is null")
                    }
                } catch (e: Exception) {
                    // No existing metadata, generate new tag and store it
                    val newTag = UUID.randomUUID().toString()
                    Log.d(TAG, "No existing tag found, generating new tag: $newTag")

                    // Create metadata with the tag using ConversationMetadataHelper
                    try {
                        val conversationGroup = org.xmtp.android.library.Conversation.Group(group)
                        val metadataBuilder =
                            ConversationMetadataProtos.ConversationCustomMetadata.newBuilder()
                                .setTag(newTag)
                        val metadata = metadataBuilder.build()

                        // Store in appData() using ConversationMetadataHelper
                        com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.storeMetadata(
                            conversationGroup,
                            metadata
                        )
                        Log.d(TAG, "Stored new tag in group appData: $newTag")
                    } catch (updateError: Exception) {
                        Log.e(TAG, "Failed to store group metadata", updateError)
                    }

                    newTag
                }

                // Get the actual secp256k1 private key for this inbox
                val privateKey = xmtpClientManager.getPrivateKeyData(inboxId)
                if (privateKey == null) {
                    Log.e(TAG, "Cannot generate invite: no private key for inbox")
                    return@launch
                }

                // Create signed invite
                val signedInvite = SignedInviteValidator.createSignedInvite(
                    conversationId = conversationId,
                    creatorInboxId = inboxId,
                    privateKey = privateKey,
                    client = client,
                    inviteTag = inviteTag,
                    name = conversation?.name,
                    description = conversation?.description,
                    imageURL = conversation?.imageUrl
                )

                // Convert to URL-safe base64 string
                val inviteCode = SignedInviteValidator.toURLSafeSlug(signedInvite)
                _inviteCode.value = inviteCode

                Log.d(TAG, "Generated invite code: $inviteCode")
                Log.d(TAG, "Invite tag: $inviteTag")
                Log.d(TAG, "Creator inbox ID: $inboxId")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate invite code", e)
            }
        }
    }

    fun startEditingInfo() {
        val currentState = _uiState.value
        if (currentState is ConversationUiState.Success) {
            _editingConversationName.value = currentState.conversation.name ?: ""
            _isEditingInfo.value = true
            _saveInfoError.value = null
        }
    }

    fun updateEditingName(name: String) {
        _editingConversationName.value = name
    }

    fun cancelEditingInfo() {
        _isEditingInfo.value = false
        _editingConversationName.value = ""
        _saveInfoError.value = null
    }

    fun saveConversationInfo() {
        val currentState = _uiState.value
        if (currentState !is ConversationUiState.Success) {
            Log.e(TAG, "Cannot save info: conversation not loaded")
            return
        }

        val conversation = currentState.conversation
        val trimmedName = _editingConversationName.value.trim()

        val hasNameChanged = trimmedName != (conversation.name ?: "")

        if (!hasNameChanged) {
            cancelEditingInfo()
            return
        }

        viewModelScope.launch {
            _isSavingInfo.value = true
            _saveInfoError.value = null

            try {
                Log.d(TAG, "Updating conversation name")
                conversationRepository.updateConversationDetails(
                    inboxId = conversation.inboxId,
                    conversationId = conversationId,
                    name = trimmedName.ifEmpty { null },
                    description = null,
                    imageUrl = null
                ).fold(
                    onSuccess = {
                        Log.d(TAG, "Conversation name updated successfully")
                        _isEditingInfo.value = false
                    },
                    onFailure = { error ->
                        throw error
                    }
                )

                Log.d(TAG, "Conversation info saved successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save conversation info", e)
                _saveInfoError.value = e.message ?: "Failed to save changes"
            } finally {
                _isSavingInfo.value = false
            }
        }
    }
}

sealed class ConversationUiState {
    object Loading : ConversationUiState()
    data class Success(
        val conversation: Conversation,
        val messages: List<Message>
    ) : ConversationUiState()

    data class Error(val message: String) : ConversationUiState()
}
