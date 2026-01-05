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

    // Cache members to prevent creating new objects on every Flow emission
    private var cachedMembers: List<com.naomiplasterer.convos.domain.model.Member> = emptyList()
    private var cachedMemberCount: Int = 0

    // Cache full conversation to prevent unnecessary UI updates when only timestamps change
    private var cachedConversation: Conversation? = null

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
                    Log.d(TAG, "💬 Messages Flow emitted: ${messages.size} messages")
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
            val conversationWithMembers = if (cachedConversation != null && conversationForComparison.meaningfullyEquals(cachedConversation)) {
                // Conversation hasn't meaningfully changed - reuse the EXACT same cached object
                // This prevents Compose from seeing a "new" object and recomposing unnecessarily
                Log.d(TAG, "Conversation hasn't meaningfully changed, reusing cached conversation object")
                cachedConversation!! // Use the exact same reference
            } else {
                // Conversation has changed - determine if we need to reload members
                val updated = if (conversationForComparison.members.isNotEmpty() &&
                                  conversationForComparison.members.size != cachedMemberCount) {
                    // Member count changed, reload
                    Log.d(TAG, "Member count changed (${cachedMemberCount} → ${conversationForComparison.members.size}), reloading members...")
                    loadConversationMembers(conversation)
                } else if (cachedMembers.isEmpty()) {
                    // First load
                    Log.d(TAG, "First load, fetching members...")
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
                else -> ConversationUiState.Success(
                    conversation = conversationWithMembers,
                    messages = emptyList()
                )
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
            val profiles = memberProfileDao.getProfilesForConversation(conversation.id)
            Log.d(TAG, "   Found ${profiles.size} profiles in database")

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
            if (messages.size < currentState.messages.size) {
                Log.w(TAG, "💬 IGNORING incomplete message list - Room emitted stale/partial data (had ${currentState.messages.size}, got ${messages.size} messages)")
                return
            }

            Log.d(TAG, "💬 Updating UI state with messages (current: ${currentState.messages.size} → new: ${messages.size})")
            _uiState.value = currentState.copy(messages = messages)
        } else {
            Log.w(TAG, "💬 Cannot update messages - UI state is not Success (state: ${currentState::class.simpleName})")
        }
    }

    fun updateMessageText(text: String) {
        _messageText.value = text
    }

    fun sendMessage() {
        val text = _messageText.value.trim()
        if (text.isEmpty() || _isSending.value) return

        // Clear message text immediately
        _messageText.value = ""

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
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to send message", error)
                    // Optionally restore the message text on failure
                    // _messageText.value = text
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
                    val groupDescription = group.description()
                    // Try to parse as hex string first (for backwards compatibility)
                    val metadataBytes = if (groupDescription.matches(Regex("^[0-9a-fA-F]+$"))) {
                        groupDescription.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    } else {
                        groupDescription.toByteArray()
                    }
                    val customMetadata =
                        ConversationMetadataProtos.ConversationCustomMetadata.parseFrom(
                            metadataBytes
                        )
                    Log.d(TAG, "Found existing tag in group metadata: ${customMetadata.tag}")
                    customMetadata.tag
                } catch (e: Exception) {
                    // No existing metadata, generate new tag and store it
                    val newTag = UUID.randomUUID().toString()
                    Log.d(TAG, "No existing tag found, generating new tag: $newTag")

                    // Create metadata with the tag
                    val metadataBuilder =
                        ConversationMetadataProtos.ConversationCustomMetadata.newBuilder()
                            .setTag(newTag)

                    // ConversationCustomMetadata only has: tag, profiles, expiresAtUnix
                    // Other fields (name, description, imageURL) are in the InvitePayload

                    val metadata = metadataBuilder.build()

                    // Store metadata in group description - must be raw bytes, not string
                    try {
                        // Convert protobuf bytes to hex string for safe storage
                        val metadataBytes = metadata.toByteArray()
                        val hexString = metadataBytes.joinToString("") { "%02x".format(it) }
                        group.updateDescription(hexString)
                        Log.d(TAG, "Updated group metadata with tag: $newTag")
                    } catch (updateError: Exception) {
                        Log.e(TAG, "Failed to update group metadata", updateError)
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
}

sealed class ConversationUiState {
    object Loading : ConversationUiState()
    data class Success(
        val conversation: Conversation,
        val messages: List<Message>
    ) : ConversationUiState()

    data class Error(val message: String) : ConversationUiState()
}
