package com.naomiplasterer.convos.ui.conversation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naomiplasterer.convos.crypto.SignedInviteValidator
import com.naomiplasterer.convos.data.repository.ConversationRepository
import com.naomiplasterer.convos.data.repository.MessageRepository
import com.naomiplasterer.convos.data.session.SessionManager
import com.naomiplasterer.convos.data.xmtp.XMTPClientManager
import com.naomiplasterer.convos.domain.model.Conversation
import com.naomiplasterer.convos.domain.model.Message
import com.naomiplasterer.convos.proto.InviteProtos
import com.naomiplasterer.convos.proto.ConversationMetadataProtos
import com.naomiplasterer.convos.codecs.ContentTypeExplodeSettings
import com.naomiplasterer.convos.codecs.ExplodeSettings
import com.naomiplasterer.convos.codecs.ExplodeSettingsCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import android.util.Log
import org.xmtp.android.library.Client
import org.xmtp.android.library.SendOptions
import org.xmtp.android.library.Conversation as XMTPConversation
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

private const val TAG = "ConversationViewModel"

@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val sessionManager: SessionManager,
    private val xmtpClientManager: XMTPClientManager
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

    private val _explodeState = MutableStateFlow<ExplodeState>(ExplodeState.Ready)
    val explodeState: StateFlow<ExplodeState> = _explodeState.asStateFlow()

    // Track if current user is the conversation creator
    private val _isCreator = MutableStateFlow(false)
    val isCreator: StateFlow<Boolean> = _isCreator.asStateFlow()

    init {
        // Register the ExplodeSettings codec
        try {
            Client.register(ExplodeSettingsCodec())
        } catch (e: Exception) {
            // Codec might already be registered, that's ok
            Log.d(TAG, "ExplodeSettings codec already registered or registration failed: ${e.message}")
        }
        loadConversation()
        loadMessages()
        syncAndStartStreaming()
    }

    private fun syncAndStartStreaming() {
        viewModelScope.launch {
            val inboxId = sessionManager.getCurrentInboxId()
            if (inboxId != null) {
                // First sync existing messages from the network
                Log.d(TAG, "Syncing messages for conversation: $conversationId")
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
            }
        }
    }

    private fun loadConversation() {
        viewModelScope.launch {
            conversationRepository.getConversation(conversationId).collectLatest { conversation ->
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
            messageRepository.getMessages(conversationId).collectLatest { messages ->
                updateUiStateWithMessages(messages)
            }
        }
    }

    private fun updateUiStateWithConversation(conversation: Conversation) {
        viewModelScope.launch {
            val conversationWithMembers = loadConversationMembers(conversation)
            val currentState = _uiState.value
            _uiState.value = when (currentState) {
                is ConversationUiState.Success -> currentState.copy(conversation = conversationWithMembers)
                else -> ConversationUiState.Success(conversation = conversationWithMembers, messages = emptyList())
            }
        }
    }

    private suspend fun loadConversationMembers(conversation: Conversation): Conversation {
        return try {
            val client = xmtpClientManager.getClient(conversation.inboxId) ?: return conversation

            val group = client.conversations.findGroup(conversation.id) ?: return conversation
            val xmtpMembers = group.members()

            // Check if current user is the creator
            val creatorInboxId = group.addedByInboxId()
            _isCreator.value = creatorInboxId == client.inboxId

            val members = xmtpMembers.map { xmtpMember ->
                com.naomiplasterer.convos.domain.model.Member(
                    inboxId = xmtpMember.inboxId,
                    addresses = listOf(xmtpMember.inboxId.take(8)),
                    permissionLevel = com.naomiplasterer.convos.domain.model.PermissionLevel.MEMBER,
                    consentState = com.naomiplasterer.convos.domain.model.ConsentState.ALLOWED,
                    addedAt = System.currentTimeMillis()
                )
            }

            conversation.copy(members = members)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load conversation members", e)
            conversation
        }
    }

    private fun updateUiStateWithMessages(messages: List<Message>) {
        val currentState = _uiState.value
        if (currentState is ConversationUiState.Success) {
            _uiState.value = currentState.copy(messages = messages)
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

            val inboxId = sessionManager.getCurrentInboxId()
            if (inboxId == null) {
                Log.e(TAG, "Cannot send message: no active inbox")
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
            val inboxId = sessionManager.getCurrentInboxId() ?: return@launch

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

    /**
     * Explodes the conversation by:
     * 1. Sending an ExplodeSettings message to notify all members
     * 2. Removing all members from the group
     * 3. Setting consent to DENIED to hide the conversation
     * 4. Leaving the conversation
     */
    fun explodeConversation() {
        if (_explodeState.value != ExplodeState.Ready) return

        viewModelScope.launch {
            try {
                _explodeState.value = ExplodeState.Exploding

                val state = _uiState.value
                if (state !is ConversationUiState.Success) {
                    _explodeState.value = ExplodeState.Error("Invalid conversation state")
                    return@launch
                }

                val inboxId = sessionManager.getCurrentInboxId()
                if (inboxId == null) {
                    _explodeState.value = ExplodeState.Error("No active inbox")
                    return@launch
                }

                val client = xmtpClientManager.getClient(inboxId)
                if (client == null) {
                    _explodeState.value = ExplodeState.Error("No XMTP client")
                    return@launch
                }

                val group = client.conversations.findGroup(conversationId)
                if (group == null) {
                    _explodeState.value = ExplodeState.Error("Group not found")
                    return@launch
                }

                // Send ExplodeSettings message to notify all members
                val expiresAt = Date()
                val explodeSettings = ExplodeSettings(expiresAt = expiresAt)

                Log.d(TAG, "Sending ExplodeSettings message...")
                withTimeout(20.seconds) {
                    group.send(
                        content = explodeSettings,
                        options = SendOptions(contentType = ContentTypeExplodeSettings)
                    )
                }
                Log.d(TAG, "ExplodeSettings message sent successfully")

                // Remove all members from the group
                val members = group.members()
                val memberInboxIds = members.map { it.inboxId }
                    .filter { it != client.inboxId } // Don't remove self yet

                if (memberInboxIds.isNotEmpty()) {
                    group.removeMembers(memberInboxIds)
                    Log.d(TAG, "Removed ${memberInboxIds.size} members from group")
                }

                // Update consent to DENIED to hide the conversation
                group.updateConsentState(org.xmtp.android.library.ConsentState.DENIED)
                conversationRepository.updateConsent(conversationId, com.naomiplasterer.convos.domain.model.ConsentState.DENIED)

                _explodeState.value = ExplodeState.Exploded

                // Leave the conversation
                group.leaveGroup()

                // Delete the conversation locally
                conversationRepository.deleteConversation(conversationId)

                Log.d(TAG, "Conversation exploded successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error exploding conversation", e)
                _explodeState.value = ExplodeState.Error(e.message ?: "Failed to explode conversation")
            }
        }
    }

    fun leaveConversation() {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                if (state !is ConversationUiState.Success) return@launch

                val inboxId = sessionManager.getCurrentInboxId()
                if (inboxId == null) {
                    Log.e(TAG, "Cannot leave: no active inbox")
                    return@launch
                }

                val client = xmtpClientManager.getClient(inboxId)
                if (client == null) {
                    Log.e(TAG, "Cannot leave: no XMTP client")
                    return@launch
                }

                val group = client.conversations.findGroup(conversationId)
                if (group != null) {
                    // Leave the group
                    group.leaveGroup()
                    Log.d(TAG, "Left group successfully")
                }

                // Update consent to hide the conversation
                conversationRepository.updateConsent(conversationId, com.naomiplasterer.convos.domain.model.ConsentState.DENIED)

                // Delete the conversation locally
                conversationRepository.deleteConversation(conversationId)

                Log.d(TAG, "Left conversation: $conversationId")
            } catch (e: Exception) {
                Log.e(TAG, "Error leaving conversation", e)
            }
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

                val inboxId = sessionManager.getCurrentInboxId()
                if (inboxId == null) {
                    Log.e(TAG, "Cannot generate invite: no active inbox")
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
                val conversation = if (state is ConversationUiState.Success) state.conversation else null

                // Try to retrieve existing tag from group metadata, or generate new one
                val inviteTag = try {
                    val groupDescription = group.description()
                    // Try to parse as hex string first (for backwards compatibility)
                    val metadataBytes = if (groupDescription.matches(Regex("^[0-9a-fA-F]+$"))) {
                        groupDescription.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    } else {
                        groupDescription.toByteArray()
                    }
                    val customMetadata = ConversationMetadataProtos.ConversationCustomMetadata.parseFrom(metadataBytes)
                    Log.d(TAG, "Found existing tag in group metadata: ${customMetadata.tag}")
                    customMetadata.tag
                } catch (e: Exception) {
                    // No existing metadata, generate new tag and store it
                    val newTag = UUID.randomUUID().toString()
                    Log.d(TAG, "No existing tag found, generating new tag: $newTag")

                    // Create metadata with the tag
                    val metadataBuilder = ConversationMetadataProtos.ConversationCustomMetadata.newBuilder()
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

                // Create signed invite
                val signedInvite = SignedInviteValidator.createSignedInvite(
                    conversationId = conversationId,
                    creatorInboxId = inboxId,
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

sealed class ExplodeState {
    object Ready : ExplodeState()
    object Exploding : ExplodeState()
    object Exploded : ExplodeState()
    data class Error(val message: String) : ExplodeState()
}
