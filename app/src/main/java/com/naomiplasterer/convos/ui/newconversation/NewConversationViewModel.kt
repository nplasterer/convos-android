package com.naomiplasterer.convos.ui.newconversation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naomiplasterer.convos.crypto.SignedInviteError
import com.naomiplasterer.convos.crypto.SignedInviteValidator
import com.naomiplasterer.convos.crypto.getCreatorInboxIdString
import com.naomiplasterer.convos.data.invite.InviteJoinRequestsManager
import com.naomiplasterer.convos.data.repository.ConversationRepository
import com.naomiplasterer.convos.data.session.SessionManager
import com.naomiplasterer.convos.data.xmtp.XMTPClientManager
import com.naomiplasterer.convos.ui.error.DisplayError
import com.naomiplasterer.convos.ui.error.IdentifiableError
import com.naomiplasterer.convos.ui.error.InviteError
import com.naomiplasterer.convos.ui.error.ConversationCreationError
import com.naomiplasterer.convos.ui.onboarding.OnboardingCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xmtp.android.library.XMTPEnvironment
import android.util.Log
import javax.inject.Inject

private const val TAG = "NewConversationViewModel"

@HiltViewModel
class NewConversationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversationRepository: ConversationRepository,
    private val sessionManager: SessionManager,
    private val xmtpClientManager: XMTPClientManager,
    private val inviteJoinRequestsManager: InviteJoinRequestsManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<NewConversationUiState>(NewConversationUiState.Idle)
    val uiState: StateFlow<NewConversationUiState> = _uiState.asStateFlow()

    private val _inviteCode = MutableStateFlow("")
    val inviteCode: StateFlow<String> = _inviteCode.asStateFlow()

    private val _mode = MutableStateFlow(NewConversationMode.SCAN)
    val mode: StateFlow<NewConversationMode> = _mode.asStateFlow()

    private val _createdConversationId = MutableStateFlow<String?>(null)
    val createdConversationId: StateFlow<String?> = _createdConversationId.asStateFlow()

    val onboardingCoordinator = OnboardingCoordinator(context, viewModelScope)

    init {
        if (_mode.value == NewConversationMode.CREATE) {
            createNewConversation()
        }

        // Listen for group matches when joining via invite
        viewModelScope.launch {
            Log.d(TAG, "Starting to listen for groupMatched events")
            inviteJoinRequestsManager.groupMatched.collect { conversationId ->
                Log.d(TAG, "Received groupMatched event for conversation: $conversationId")
                Log.d(TAG, "Current UI state before transition: ${_uiState.value}")
                onboardingCoordinator.isWaitingForInviteAcceptance = false
                onboardingCoordinator.inviteWasAccepted(conversationId)
                _uiState.value = NewConversationUiState.Success(conversationId)
                Log.d(TAG, "Transitioned to Success state with conversation: $conversationId")
            }
        }
    }

    fun setMode(mode: NewConversationMode) {
        _mode.value = mode
        when (mode) {
            NewConversationMode.SCAN -> {
                _inviteCode.value = ""
                _uiState.value = NewConversationUiState.Idle
            }
            NewConversationMode.CREATE -> {
                createNewConversation()
            }
            NewConversationMode.MANUAL -> {
                _uiState.value = NewConversationUiState.Idle
            }
        }
    }

    fun createNewConversation() {
        viewModelScope.launch {
            _uiState.value = NewConversationUiState.Creating

            try {
                Log.d(TAG, "Creating new conversation")

                // ALWAYS create a new inbox for each conversation (multi-inbox architecture)
                Log.d(TAG, "Creating new inbox for new conversation")
                val newInboxId = sessionManager.createSession(
                    address = "random",
                    environment = XMTPEnvironment.PRODUCTION
                ).getOrThrow()

                val activeClient = xmtpClientManager.getClient(newInboxId)
                    ?: throw IllegalStateException("Failed to get client after creating session")

                val inboxId = activeClient.inboxId
                Log.d(TAG, "New conversation client ready with inboxId: $inboxId")

                // Create a new group with empty member list
                // Members can join later via invite code
                val group = activeClient.conversations.newGroup(emptyList())
                val conversationId = group.id

                Log.d(TAG, "Created new conversation with ID: $conversationId")
                Log.d(TAG, "Conversation ID format - length: ${conversationId.length}, contains hex: ${conversationId.matches(Regex("^[0-9a-fA-F]+$"))}")

                // Generate and store a tag for this conversation
                val inviteTag = java.util.UUID.randomUUID().toString()
                Log.d(TAG, "Generated invite tag for new conversation: $inviteTag")

                try {
                    // Create metadata with the tag using the helper
                    val metadata = com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.generateMetadata(
                        tag = inviteTag
                    )

                    // Store metadata in group appData (matching iOS)
                    // Convert the Group to Conversation.Group
                    val conversationGroup = org.xmtp.android.library.Conversation.Group(group)
                    com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.storeMetadata(conversationGroup, metadata)
                    Log.d(TAG, "Stored metadata with tag in group appData")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to store initial metadata", e)
                }

                // Log the group's name
                @Suppress("DEPRECATION")
                val groupName = try { group.name } catch (e: Exception) { null }
                Log.d(TAG, "Group name from XMTP: ${groupName ?: "null"}")

                // Sync the conversation to the local database
                conversationRepository.syncConversations(inboxId).onFailure { error ->
                    Log.e(TAG, "Failed to sync conversations after creation", error)
                }

                // Set consent to ALLOWED so the conversation appears in the list
                conversationRepository.updateConsent(conversationId, com.naomiplasterer.convos.domain.model.ConsentState.ALLOWED)
                Log.d(TAG, "Set consent to ALLOWED for new conversation: $conversationId")

                _createdConversationId.value = conversationId
                _uiState.value = NewConversationUiState.Success(conversationId)

                // Start onboarding flow for new conversation
                onboardingCoordinator.start(conversationId)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to create conversation", e)
                _uiState.value = NewConversationUiState.Error(
                    IdentifiableError(
                        error = ConversationCreationError.UnknownError(e.message)
                    )
                )
            }
        }
    }

    fun updateInviteCode(code: String) {
        _inviteCode.value = code
    }

    fun onQRCodeScanned(code: String) {
        Log.d(TAG, "QR code scanned: $code")
        val inviteCode = extractInviteCode(code)
        if (inviteCode != null) {
            _inviteCode.value = inviteCode
            joinConversation(inviteCode)
        } else {
            _uiState.value = NewConversationUiState.Error(
                IdentifiableError(error = InviteError.InvalidFormat)
            )
        }
    }

    fun joinConversation(code: String = _inviteCode.value) {
        if (code.isBlank()) {
            _uiState.value = NewConversationUiState.Error(
                IdentifiableError(
                    error = com.naomiplasterer.convos.ui.error.GenericDisplayError(
                        title = "No Invite Code",
                        description = "Please enter an invite code"
                    )
                )
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = NewConversationUiState.Joining

            try {
                Log.d(TAG, "Joining conversation with signed invite code: $code")

                // ALWAYS create a new inbox when joining a conversation (multi-inbox architecture)
                Log.d(TAG, "Creating new inbox for joining conversation")
                val newInboxId = sessionManager.createSession(
                    address = "random",
                    environment = XMTPEnvironment.PRODUCTION
                ).getOrThrow()

                val activeClient = xmtpClientManager.getClient(newInboxId)
                    ?: throw IllegalStateException("Failed to get client after creating session")

                Log.d(TAG, "New join client ready with inboxId: ${activeClient.inboxId}")

                // Add validating state
                _uiState.value = NewConversationUiState.Validating

                val signedInvite = try {
                    SignedInviteValidator.parseFromURLSafeSlug(code)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse signed invite", e)
                    _uiState.value = NewConversationUiState.Error(
                        IdentifiableError(error = InviteError.InvalidFormat)
                    )
                    return@launch
                }

                if (SignedInviteValidator.hasExpired(signedInvite)) {
                    _uiState.value = NewConversationUiState.Error(
                        IdentifiableError(error = InviteError.Expired)
                    )
                    return@launch
                }

                if (SignedInviteValidator.conversationHasExpired(signedInvite)) {
                    _uiState.value = NewConversationUiState.Error(
                        IdentifiableError(error = InviteError.ConversationExpired)
                    )
                    return@launch
                }

                // Parse payload from bytes
                val payload = SignedInviteValidator.getPayload(signedInvite)

                // Add validated state
                _uiState.value = NewConversationUiState.Validated(
                    conversationName = payload.name,
                    conversationDescription = payload.description,
                    conversationImageURL = payload.imageURL
                )

                // Transition to joining
                _uiState.value = NewConversationUiState.Joining

                val creatorInboxId = payload.getCreatorInboxIdString()
                Log.d(TAG, "Invite created by inbox: $creatorInboxId")

                activeClient.conversations.sync()

                val allDms = activeClient.conversations.listDms()
                val creatorDm = allDms.find { dm ->
                    try {
                        dm.peerInboxId == creatorInboxId
                    } catch (e: Exception) {
                        false
                    }
                } ?: activeClient.conversations.findOrCreateDm(creatorInboxId)

                Log.d(TAG, "Sending join request to creator via DM")
                creatorDm.send(code)

                // Hide this DM from the conversation list as it's only for invite protocol
                // The creator will handle it on their end
                creatorDm.updateConsentState(org.xmtp.android.library.ConsentState.DENIED)
                Log.d(TAG, "Set DM consent to DENIED to hide it from conversation list")

                inviteJoinRequestsManager.storePendingInvite(signedInvite)
                Log.d(TAG, "Stored pending invite with tag: ${payload.tag}")

                // Set waiting for invite acceptance state
                onboardingCoordinator.isWaitingForInviteAcceptance = true

                _uiState.value = NewConversationUiState.WaitingForApproval(
                    message = "Join request sent! Waiting for ${payload.name ?: "the creator"} to approve.",
                    conversationName = payload.name,
                    conversationDescription = payload.description,
                    conversationImageURL = payload.imageURL
                )

            } catch (e: SignedInviteError) {
                Log.e(TAG, "Signed invite validation error", e)
                _uiState.value = NewConversationUiState.Error(
                    IdentifiableError(error = InviteError.SignatureInvalid)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to join conversation", e)
                _uiState.value = NewConversationUiState.Error(
                    IdentifiableError(error = InviteError.UnknownError(e.message))
                )
            }
        }
    }

    private fun extractInviteCode(qrCodeData: String): String? {
        Log.d(TAG, "Extracting invite code from QR data: $qrCodeData")

        val result = when {
            // Android format: convos://i/{code}
            qrCodeData.startsWith("convos://i/") -> {
                qrCodeData.removePrefix("convos://i/").also {
                    Log.d(TAG, "Matched Android deep link format, extracted code length: ${it.length}")
                }
            }
            // Android format: https://convos.app/i/{code}
            qrCodeData.startsWith("https://convos.app/i/") -> {
                qrCodeData.removePrefix("https://convos.app/i/").also {
                    Log.d(TAG, "Matched Android HTTPS format, extracted code length: ${it.length}")
                }
            }
            // iOS format with query param: https://{domain}/v2?i={code}
            qrCodeData.contains("/v2?i=") -> {
                val regex = Regex("/v2\\?i=([^&]+)")
                val match = regex.find(qrCodeData)
                val code = match?.groupValues?.getOrNull(1)
                Log.d(TAG, "Matched iOS v2 query param format, extracted code: ${code?.take(20)}...")
                code
            }
            // iOS format with path: https://popup.convos.org/{conversationId}
            // Also matches: https://convos.app/v2?i={code} style URLs
            qrCodeData.startsWith("https://") || qrCodeData.startsWith("http://") -> {
                try {
                    // Extract the path after the domain
                    val url = qrCodeData.removePrefix("https://").removePrefix("http://")
                    val pathStart = url.indexOf('/')
                    val code = if (pathStart >= 0) {
                        // Get everything after the first slash
                        url.substring(pathStart + 1).also {
                            Log.d(TAG, "Matched iOS URL path format, extracted code: ${it.take(20)}...")
                        }
                    } else {
                        Log.w(TAG, "No path found in URL: $qrCodeData")
                        null
                    }
                    code
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse URL: $qrCodeData", e)
                    null
                }
            }
            // Raw invite code (base64url or alphanumeric characters)
            qrCodeData.matches(Regex("^[a-zA-Z0-9-_]+$")) -> {
                Log.d(TAG, "Matched raw invite code format, length: ${qrCodeData.length}")
                qrCodeData
            }
            else -> {
                Log.w(TAG, "QR code data did not match any known format: $qrCodeData")
                null
            }
        }

        if (result != null) {
            Log.d(TAG, "Successfully extracted invite code (length: ${result.length})")
        } else {
            Log.e(TAG, "Failed to extract invite code from: $qrCodeData")
        }

        return result
    }

    fun resetState() {
        _uiState.value = NewConversationUiState.Idle
        _inviteCode.value = ""
    }
}

sealed class NewConversationUiState {
    object Idle : NewConversationUiState()
    object Creating : NewConversationUiState()
    object Validating : NewConversationUiState()
    data class Validated(
        val conversationName: String?,
        val conversationDescription: String?,
        val conversationImageURL: String?
    ) : NewConversationUiState()
    object Joining : NewConversationUiState()
    data class WaitingForApproval(
        val message: String,
        val conversationName: String? = null,
        val conversationDescription: String? = null,
        val conversationImageURL: String? = null
    ) : NewConversationUiState()
    data class Success(val conversationId: String) : NewConversationUiState()
    data class Error(
        val error: IdentifiableError
    ) : NewConversationUiState() {
        val message: String get() = error.description
    }
}

enum class NewConversationMode {
    CREATE,
    SCAN,
    MANUAL
}
