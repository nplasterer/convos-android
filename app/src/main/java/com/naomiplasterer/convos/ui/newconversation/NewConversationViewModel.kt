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
import com.naomiplasterer.convos.ui.error.IdentifiableError
import com.naomiplasterer.convos.ui.error.InviteError
import com.naomiplasterer.convos.ui.error.ConversationCreationError
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
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

    private var lastScannedCode: String? = null

    init {
        // Don't auto-create in init - let setMode handle it
        // This prevents issues when ViewModel is reused

        // Listen for group matches when joining via invite
        viewModelScope.launch {
            Log.d(TAG, "Starting to listen for groupMatched events")
            inviteJoinRequestsManager.groupMatched.collect { conversationId ->
                Log.d(TAG, "Received groupMatched event for conversation: $conversationId")
                Log.d(TAG, "Current mode: ${_mode.value}, UI state: ${_uiState.value}")

                // Only process groupMatched events if we're actually in a joining flow
                if (_uiState.value is NewConversationUiState.WaitingForApproval ||
                    _uiState.value is NewConversationUiState.Joining
                ) {
                    Log.d(TAG, "Processing groupMatched event")
                    _uiState.value = NewConversationUiState.Success(conversationId)
                    Log.d(TAG, "Transitioned to Success state with conversation: $conversationId")
                } else {
                    Log.w(
                        TAG,
                        "Ignoring groupMatched event - not in joining flow (state: ${_uiState.value})"
                    )
                }
            }
        }
    }

    fun setMode(mode: NewConversationMode) {
        Log.d(TAG, "🔄 Switching mode from ${_mode.value} to $mode")

        // Reset all state when switching modes to prevent stale state
        _inviteCode.value = ""
        _createdConversationId.value = null
        lastScannedCode = null
        _uiState.value = NewConversationUiState.Idle

        _mode.value = mode
        when (mode) {
            NewConversationMode.SCAN -> {
                Log.d(TAG, "   Switched to SCAN mode - ready for new QR code")
            }

            NewConversationMode.CREATE -> {
                Log.d(TAG, "   Switched to CREATE mode - starting conversation creation")
                createNewConversation()
            }

            NewConversationMode.MANUAL -> {
                Log.d(TAG, "   Switched to MANUAL mode - ready for manual input")
            }
        }
    }

    fun createNewConversation() {
        viewModelScope.launch {
            _uiState.value = NewConversationUiState.Creating
            var createdInboxId: String? = null

            try {
                Log.d(TAG, "🆕 Creating new conversation")

                // ALWAYS create a new inbox for each conversation (multi-inbox architecture)
                Log.d(TAG, "📱 Creating new inbox for new conversation")
                val newInboxId = sessionManager.createSession(
                    address = "random",
                    environment = XMTPEnvironment.PRODUCTION
                ).getOrThrow()
                createdInboxId = newInboxId

                val activeClient = xmtpClientManager.getClient(newInboxId)
                    ?: throw IllegalStateException("Failed to get client after creating session")

                val inboxId = activeClient.inboxId
                Log.d(TAG, "✅ New conversation client ready with inboxId: $inboxId")

                // Create a new group with empty member list
                // Members can join later via invite code
                Log.d(TAG, "👥 Creating new group...")
                val group = activeClient.conversations.newGroup(emptyList())
                val conversationId = group.id

                Log.d(TAG, "✅ Created new group with ID: $conversationId")
                Log.d(
                    TAG,
                    "   ID length: ${conversationId.length}, format check: ${
                        conversationId.matches(Regex("^[0-9a-fA-F]+$"))
                    }"
                )

                // Generate and store a tag for this conversation
                val inviteTag = java.util.UUID.randomUUID().toString()
                Log.d(TAG, "🏷️  Generated invite tag: $inviteTag")

                try {
                    // Create metadata with the tag using the helper
                    val metadata =
                        com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.generateMetadata(
                            tag = inviteTag
                        )

                    // Store metadata in group appData (matching iOS)
                    // Convert the Group to Conversation.Group
                    val conversationGroup = org.xmtp.android.library.Conversation.Group(group)
                    com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.storeMetadata(
                        conversationGroup,
                        metadata
                    )
                    Log.d(TAG, "💾 Stored metadata with tag in group appData")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to store initial metadata", e)
                }

                // Log the group's name
                @Suppress("DEPRECATION")
                val groupName = try {
                    group.name
                } catch (e: Exception) {
                    null
                }
                Log.d(TAG, "📝 Group name from XMTP: ${groupName ?: "null"}")

                // Sync the conversation to the local database
                Log.d(TAG, "🔄 Syncing conversation to local database...")
                val syncResult = conversationRepository.syncConversations(inboxId)
                syncResult.onSuccess {
                    Log.d(TAG, "✅ Successfully synced conversations to database")
                }.onFailure { error ->
                    Log.e(TAG, "❌ Failed to sync conversations after creation", error)
                    throw error // Re-throw to prevent setting success state
                }

                // Verify the conversation exists in database
                Log.d(TAG, "🔍 Verifying conversation exists in database...")
                val dbConversation = conversationRepository.getConversation(conversationId)
                    .catch { e ->
                        Log.e(TAG, "❌ Error querying conversation from database", e)
                        emit(null)
                    }
                    .first()

                if (dbConversation == null) {
                    Log.e(TAG, "❌ CRITICAL: Conversation not found in database after sync!")
                    throw IllegalStateException("Conversation $conversationId not found in database after sync")
                }

                Log.d(TAG, "✅ Conversation verified in database:")
                Log.d(TAG, "   ID: ${dbConversation.id}")
                Log.d(TAG, "   Name: ${dbConversation.name}")
                Log.d(TAG, "   Consent: ${dbConversation.consent}")
                Log.d(TAG, "   Tag: ${dbConversation.inviteTag}")

                // Set consent to ALLOWED so the conversation appears in the list
                conversationRepository.updateConsent(
                    conversationId,
                    com.naomiplasterer.convos.domain.model.ConsentState.ALLOWED
                )
                Log.d(TAG, "✅ Set consent to ALLOWED for new conversation: $conversationId")

                _createdConversationId.value = conversationId
                _uiState.value = NewConversationUiState.Success(conversationId)
                Log.d(TAG, "🎉 Successfully created conversation: $conversationId")

            } catch (e: CancellationException) {
                // User backed out during creation - this is normal, not an error
                Log.d(TAG, "🔙 Conversation creation cancelled by user")

                // Clean up partially created inbox if it was created
                createdInboxId?.let { inboxId ->
                    try {
                        Log.d(TAG, "🧹 Cleaning up partially created inbox: $inboxId")
                        xmtpClientManager.removeClient(inboxId)
                        // Note: The grace period in cleanupExpiredInboxes will handle database cleanup
                    } catch (cleanupError: Exception) {
                        Log.e(TAG, "Failed to cleanup inbox during cancellation", cleanupError)
                    }
                }

                // Reset to idle state
                _uiState.value = NewConversationUiState.Idle

                // Re-throw to properly cancel the coroutine
                throw e
            } catch (e: Exception) {
                // Check if this exception wraps a CancellationException (XMTP SDK wraps it)
                val cause = e.cause
                if (cause is CancellationException) {
                    Log.d(
                        TAG,
                        "🔙 Conversation creation cancelled (wrapped in ${e.javaClass.simpleName})"
                    )

                    // Clean up partially created inbox if it was created
                    createdInboxId?.let { inboxId ->
                        try {
                            Log.d(TAG, "🧹 Cleaning up partially created inbox: $inboxId")
                            xmtpClientManager.removeClient(inboxId)
                        } catch (cleanupError: Exception) {
                            Log.e(TAG, "Failed to cleanup inbox during cancellation", cleanupError)
                        }
                    }

                    // Reset to idle state
                    _uiState.value = NewConversationUiState.Idle

                    // Re-throw the original cancellation
                    throw cause
                }

                // Real error - not a cancellation
                Log.e(TAG, "❌ Failed to create conversation", e)
                Log.e(TAG, "   Error type: ${e.javaClass.simpleName}")
                Log.e(TAG, "   Error message: ${e.message}")
                Log.e(TAG, "   Stack trace:", e)
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
        Log.d(TAG, "📷 QR code scanned!")
        Log.d(TAG, "   Raw QR data: $code")
        Log.d(TAG, "   Raw data length: ${code.length}")
        Log.d(TAG, "   Current state: ${_uiState.value}")
        Log.d(TAG, "   Last scanned code: $lastScannedCode")

        // Check if this is a duplicate scan
        if (code == lastScannedCode && _uiState.value !is NewConversationUiState.Error) {
            Log.w(TAG, "⚠️  Ignoring duplicate QR code scan")
            return
        }

        // Check if we're already processing a join
        if (_uiState.value is NewConversationUiState.Joining ||
            _uiState.value is NewConversationUiState.Validating ||
            _uiState.value is NewConversationUiState.WaitingForApproval
        ) {
            Log.w(TAG, "⚠️  Already processing a join request, ignoring new scan")
            return
        }

        lastScannedCode = code
        Log.d(TAG, "   Stored as last scanned code")

        val inviteCode = extractInviteCode(code)
        if (inviteCode != null) {
            Log.d(TAG, "✅ Successfully extracted invite code, length: ${inviteCode.length}")
            _inviteCode.value = inviteCode
            joinConversation(inviteCode)
        } else {
            Log.e(TAG, "❌ Failed to extract invite code - invalid format")
            Log.e(TAG, "   QR data was: $code")
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
            var createdInboxId: String? = null

            try {
                Log.d(TAG, "🔐 Joining conversation with signed invite code: $code")

                // First, parse and validate the invite
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
                val inviteTag = payload.tag

                Log.d(TAG, "📋 Invite details - Tag: $inviteTag, Name: ${payload.name}")

                // Check if we already have this conversation in the database by tag
                Log.d(TAG, "🔍 Checking if conversation with tag '$inviteTag' already exists...")
                val existingConversation = conversationRepository.findConversationByTag(inviteTag)

                if (existingConversation != null) {
                    Log.d(TAG, "✅ Found existing conversation: ${existingConversation.id}")
                    Log.d(TAG, "   Conversation name: ${existingConversation.name}")
                    Log.d(TAG, "   Consent state: ${existingConversation.consent}")

                    if (existingConversation.consent == com.naomiplasterer.convos.domain.model.ConsentState.ALLOWED) {
                        Log.d(TAG, "🎉 Conversation already joined! Navigating directly to it.")
                        _uiState.value = NewConversationUiState.Success(existingConversation.id)
                        return@launch
                    } else {
                        Log.d(
                            TAG,
                            "⚠️  Conversation exists but consent is ${existingConversation.consent}"
                        )
                        // Continue with join process in case approval is still pending
                    }
                } else {
                    Log.d(TAG, "❌ No existing conversation found with tag: $inviteTag")
                }

                // Show validated state
                _uiState.value = NewConversationUiState.Validated(
                    conversationName = payload.name,
                    conversationDescription = payload.description,
                    conversationImageURL = payload.imageURL
                )

                // ALWAYS create a new inbox when joining a conversation (multi-inbox architecture)
                Log.d(TAG, "📱 Creating new inbox for joining conversation")
                val newInboxId = sessionManager.createSession(
                    address = "random",
                    environment = XMTPEnvironment.PRODUCTION
                ).getOrThrow()
                createdInboxId = newInboxId

                val activeClient = xmtpClientManager.getClient(newInboxId)
                    ?: throw IllegalStateException("Failed to get client after creating session")

                Log.d(TAG, "✅ New join client ready with inboxId: ${activeClient.inboxId}")

                // Transition to joining
                _uiState.value = NewConversationUiState.Joining

                val creatorInboxId = payload.getCreatorInboxIdString()
                Log.d(TAG, "👤 Invite created by inbox: $creatorInboxId")

                activeClient.conversations.sync()

                val allDms = activeClient.conversations.listDms()
                val creatorDm = allDms.find { dm ->
                    try {
                        dm.peerInboxId == creatorInboxId
                    } catch (e: Exception) {
                        false
                    }
                } ?: activeClient.conversations.findOrCreateDm(creatorInboxId)

                Log.d(TAG, "📨 Sending join request to creator via DM")
                creatorDm.send(code)

                // Hide this DM from the conversation list as it's only for invite protocol
                // The creator will handle it on their end
                creatorDm.updateConsentState(org.xmtp.android.library.ConsentState.DENIED)
                Log.d(TAG, "🔕 Set DM consent to DENIED to hide it from conversation list")

                inviteJoinRequestsManager.storePendingInvite(signedInvite)
                Log.d(TAG, "💾 Stored pending invite with tag: ${payload.tag}")

                // Start conversation streaming to detect when we're added to the group (matches iOS)
                // The stream will call checkGroupForPendingInvite which emits groupMatched when tag matches
                Log.d(TAG, "📡 Starting conversation streaming for real-time group detection")
                conversationRepository.startConversationStreaming(newInboxId)

                _uiState.value = NewConversationUiState.WaitingForApproval(
                    message = "Join request sent! Waiting for approval.",
                    conversationName = payload.name,
                )

            } catch (e: CancellationException) {
                // User backed out during join - this is normal, not an error
                Log.d(TAG, "🔙 Join conversation cancelled by user")

                // Clean up partially created inbox if it was created
                createdInboxId?.let { inboxId ->
                    try {
                        Log.d(TAG, "🧹 Cleaning up partially created inbox: $inboxId")
                        xmtpClientManager.removeClient(inboxId)
                        // Note: The grace period in cleanupExpiredInboxes will handle database cleanup
                    } catch (cleanupError: Exception) {
                        Log.e(TAG, "Failed to cleanup inbox during cancellation", cleanupError)
                    }
                }

                // Reset to idle state
                _uiState.value = NewConversationUiState.Idle

                // Re-throw to properly cancel the coroutine
                throw e
            } catch (e: SignedInviteError) {
                Log.e(TAG, "❌ Signed invite validation error", e)
                _uiState.value = NewConversationUiState.Error(
                    IdentifiableError(error = InviteError.SignatureInvalid)
                )
            } catch (e: Exception) {
                // Check if this exception wraps a CancellationException (XMTP SDK wraps it)
                val cause = e.cause
                if (cause is CancellationException) {
                    Log.d(
                        TAG,
                        "🔙 Join conversation cancelled (wrapped in ${e.javaClass.simpleName})"
                    )

                    // Clean up partially created inbox if it was created
                    createdInboxId?.let { inboxId ->
                        try {
                            Log.d(TAG, "🧹 Cleaning up partially created inbox: $inboxId")
                            xmtpClientManager.removeClient(inboxId)
                        } catch (cleanupError: Exception) {
                            Log.e(TAG, "Failed to cleanup inbox during cancellation", cleanupError)
                        }
                    }

                    // Reset to idle state
                    _uiState.value = NewConversationUiState.Idle

                    // Re-throw the original cancellation
                    throw cause
                }

                // Real error - not a cancellation
                Log.e(TAG, "❌ Failed to join conversation", e)
                _uiState.value = NewConversationUiState.Error(
                    IdentifiableError(error = InviteError.UnknownError(e.message))
                )
            }
        }
    }

    private fun extractInviteCode(qrCodeData: String): String? {
        Log.d(TAG, "🔍 Extracting invite code from QR data")
        Log.d(TAG, "   Full QR data: $qrCodeData")
        Log.d(TAG, "   Length: ${qrCodeData.length}")
        Log.d(TAG, "   First 100 chars: ${qrCodeData.take(100)}")

        // Log what patterns we're checking
        Log.d(TAG, "   Checking patterns:")
        Log.d(TAG, "      - Starts with 'convos://i/': ${qrCodeData.startsWith("convos://i/")}")
        Log.d(
            TAG,
            "      - Starts with 'https://convos.app/i/': ${qrCodeData.startsWith("https://convos.app/i/")}"
        )
        Log.d(TAG, "      - Contains '/v2?i=': ${qrCodeData.contains("/v2?i=")}")
        Log.d(
            TAG,
            "      - Starts with 'https://' or 'http://': ${
                qrCodeData.startsWith("https://") || qrCodeData.startsWith("http://")
            }"
        )
        Log.d(
            TAG,
            "      - Matches raw base64url: ${qrCodeData.matches(Regex("^[a-zA-Z0-9-_]+$"))}"
        )

        val result = when {
            // Android format: convos://i/{code}
            qrCodeData.startsWith("convos://i/") -> {
                qrCodeData.removePrefix("convos://i/").also {
                    Log.d(TAG, "✅ Matched Android deep link format")
                    Log.d(TAG, "   Extracted code length: ${it.length}")
                    Log.d(TAG, "   Extracted code: ${it.take(50)}...")
                }
            }
            // Android format: https://convos.app/i/{code}
            qrCodeData.startsWith("https://convos.app/i/") -> {
                qrCodeData.removePrefix("https://convos.app/i/").also {
                    Log.d(TAG, "✅ Matched Android HTTPS format")
                    Log.d(TAG, "   Extracted code length: ${it.length}")
                    Log.d(TAG, "   Extracted code: ${it.take(50)}...")
                }
            }
            // iOS format with query param: https://{domain}/v2?i={code}
            qrCodeData.contains("/v2?i=") -> {
                val regex = Regex("/v2\\?i=([^&]+)")
                val match = regex.find(qrCodeData)
                val code = match?.groupValues?.getOrNull(1)
                Log.d(TAG, "✅ Matched iOS v2 query param format")
                Log.d(TAG, "   Extracted code: ${code?.take(50)}...")
                code
            }
            // iOS format with path: https://popup.convos.org/{conversationId}
            // Also matches: https://convos.app/v2?i={code} style URLs
            qrCodeData.startsWith("https://") || qrCodeData.startsWith("http://") -> {
                try {
                    Log.d(TAG, "🔍 Trying to extract from generic HTTPS URL")
                    // Extract the path after the domain
                    val url = qrCodeData.removePrefix("https://").removePrefix("http://")
                    Log.d(TAG, "   URL without protocol: $url")
                    val pathStart = url.indexOf('/')
                    Log.d(TAG, "   Path starts at index: $pathStart")
                    val code = if (pathStart >= 0) {
                        // Get everything after the first slash
                        url.substring(pathStart + 1).also {
                            Log.d(TAG, "✅ Matched generic URL path format")
                            Log.d(TAG, "   Extracted path: ${it.take(50)}...")
                        }
                    } else {
                        Log.w(TAG, "❌ No path found in URL: $qrCodeData")
                        null
                    }
                    code
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to parse URL: $qrCodeData", e)
                    null
                }
            }
            // Raw invite code (base64url or alphanumeric characters)
            qrCodeData.matches(Regex("^[a-zA-Z0-9-_]+$")) -> {
                Log.d(TAG, "✅ Matched raw invite code format")
                Log.d(TAG, "   Code length: ${qrCodeData.length}")
                Log.d(TAG, "   Code: ${qrCodeData.take(50)}...")
                qrCodeData
            }

            else -> {
                Log.e(TAG, "❌ QR code data did not match ANY known format!")
                Log.e(TAG, "   Full data: $qrCodeData")
                Log.e(
                    TAG,
                    "   Hex dump of first 50 bytes: ${
                        qrCodeData.take(50).toByteArray().joinToString(" ") { "%02x".format(it) }
                    }"
                )
                null
            }
        }

        if (result != null) {
            Log.d(TAG, "✅ Successfully extracted invite code")
            Log.d(TAG, "   Final code length: ${result.length}")
            Log.d(TAG, "   Final code: ${result.take(100)}")
        } else {
            Log.e(TAG, "❌ Failed to extract invite code!")
        }

        return result
    }

    fun resetState() {
        Log.d(TAG, "🔄 Resetting NewConversation state")
        _uiState.value = NewConversationUiState.Idle
        _inviteCode.value = ""
        lastScannedCode = null
        Log.d(TAG, "   State reset complete - ready for new scan/join")
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
