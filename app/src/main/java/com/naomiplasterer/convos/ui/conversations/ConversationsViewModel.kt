package com.naomiplasterer.convos.ui.conversations

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naomiplasterer.convos.data.repository.ConversationRepository
import com.naomiplasterer.convos.data.repository.MessageRepository
import com.naomiplasterer.convos.data.session.SessionManager
import com.naomiplasterer.convos.data.session.SessionState
import com.naomiplasterer.convos.data.xmtp.XMTPClientManager
import com.naomiplasterer.convos.domain.model.Conversation
import com.naomiplasterer.convos.domain.model.ConsentState
import com.naomiplasterer.convos.domain.model.meaningfullyEquals
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.util.Log
import javax.inject.Inject
import androidx.core.content.edit

private const val TAG = "ConversationsViewModel"

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val sessionManager: SessionManager,
    private val xmtpClientManager: XMTPClientManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<ConversationsUiState>(ConversationsUiState.Loading)
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()

    private val _selectedConversationId = MutableStateFlow<String?>(null)

    private val prefs = context.getSharedPreferences("convos_prefs", Context.MODE_PRIVATE)

    private val _hasCreatedMoreThanOneConvo = MutableStateFlow(
        prefs.getBoolean("hasCreatedMoreThanOneConvo", false)
    )
    val hasCreatedMoreThanOneConvo: StateFlow<Boolean> = _hasCreatedMoreThanOneConvo.asStateFlow()

    // Track initial sync completion to match iOS ValueObservation pattern
    private var isInitialSyncComplete = false

    // Track if we've ever shown conversations (prevents showing Loading when all conversations expire)
    private var hasEverHadConversations = false


    init {
        observeSession()
        startExpirationCheck()
        startPeriodicFallbackSync()
    }

    private fun observeSession() {
        viewModelScope.launch {
            sessionManager.sessionState.collectLatest { sessionState ->
                when (sessionState) {
                    is SessionState.Active -> {
                        loadAllConversations()
                    }

                    is SessionState.NoSession -> {
                        _uiState.value = ConversationsUiState.NoSession
                    }

                    is SessionState.Creating -> {
                        _uiState.value = ConversationsUiState.Loading
                    }

                    is SessionState.Error -> {
                        _uiState.value = ConversationsUiState.Error(sessionState.message)
                    }
                }
            }
        }
    }

    private suspend fun loadAllConversations() {
        try {
            Log.d(TAG, "loadAllConversations: Starting to load conversations from all inboxes")

            // Reset sync flags when starting a new load
            isInitialSyncComplete = false
            hasEverHadConversations = false

            // First, clean up any expired inboxes
            conversationRepository.cleanupExpiredInboxes()

            // Get all inbox IDs
            val allInboxIds = sessionManager.getAllInboxIds()
            Log.d(TAG, "loadAllConversations: Found ${allInboxIds.size} inboxes")

            if (allInboxIds.isEmpty()) {
                isInitialSyncComplete = true
                _uiState.value = ConversationsUiState.Empty
                return
            }

            // Start observing database IMMEDIATELY (matches iOS ValueObservation pattern)
            // This will show cached conversations right away if they exist
            observeAllConversations(allInboxIds)

            // Run network sync in parallel (don't block database observation)
            viewModelScope.launch {
                for (inboxId in allInboxIds) {
                    // Ensure client is loaded before syncing
                    sessionManager.ensureClientLoaded(inboxId).fold(
                        onSuccess = {
                            conversationRepository.syncConversations(inboxId).fold(
                                onSuccess = {
                                    Log.d(
                                        TAG,
                                        "loadAllConversations: Sync completed for inbox: $inboxId"
                                    )

                                    // Sync messages in parallel to populate last message previews faster
                                    // The database Flow will automatically update the UI when messages are inserted
                                    val conversations = conversationRepository.getConversations(inboxId).first()
                                    val messageSyncJobs = conversations.map { conversation ->
                                        async {
                                            messageRepository.syncMessages(inboxId, conversation.id).fold(
                                                onSuccess = {
                                                    Log.d(TAG, "Synced messages for: ${conversation.name ?: conversation.id}")
                                                    // Start message streaming for real-time updates
                                                    messageRepository.startMessageStreaming(inboxId, conversation.id)
                                                },
                                                onFailure = { error ->
                                                    Log.w(TAG, "Failed to sync messages for: ${conversation.id}", error)
                                                }
                                            )
                                        }
                                    }
                                    // Wait for all message syncs to complete
                                    messageSyncJobs.awaitAll()
                                    Log.d(TAG, "All message syncs completed for inbox: $inboxId")
                                },
                                onFailure = { error ->
                                    Log.e(
                                        TAG,
                                        "loadAllConversations: Sync failed for inbox: $inboxId",
                                        error
                                    )
                                }
                            )
                            // Start streaming for real-time updates (matches iOS streaming architecture)
                            conversationRepository.startConversationStreaming(inboxId)
                            // Start message streaming to process join requests in real-time
                            conversationRepository.startMessageStreaming(inboxId)
                        },
                        onFailure = { error ->
                            Log.e(
                                TAG,
                                "loadAllConversations: Failed to load client for inbox: $inboxId",
                                error
                            )
                        }
                    )
                }
                // Mark initial sync as complete after all inboxes finish
                isInitialSyncComplete = true
                Log.d(TAG, "loadAllConversations: Initial sync complete")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load all conversations", e)
            isInitialSyncComplete = true
            _uiState.value = ConversationsUiState.Error(e.message ?: "Unknown error")
        }
    }

    private suspend fun observeAllConversations(inboxIds: List<String>) {
        // Combine conversations from all inboxes
        conversationRepository.getConversationsFromAllInboxes(inboxIds)
            .distinctUntilChanged { old, new ->
                // Use meaningful equality to prevent updates when only timestamps change
                old.meaningfullyEquals(new)
            }
            .collectLatest { allConversations ->
                Log.d(
                    TAG,
                    "observeAllConversations: Received ${allConversations.size} total conversations, syncComplete=$isInitialSyncComplete"
                )

                // Log conversation order to track proper sorting by lastMessageAt
                if (allConversations.isNotEmpty()) {
                    Log.d(TAG, "Conversation order (by lastMessageAt):")
                    allConversations.take(5).forEach { conv ->
                        Log.d(
                            TAG,
                            "  - ${conv.name ?: conv.id.take(8)}: lastMessageAt=${conv.lastMessageAt}, createdAt=${conv.createdAt}, preview=${conv.lastMessagePreview?.take(30)}"
                        )
                    }
                }

                if (allConversations.size > 1 && !_hasCreatedMoreThanOneConvo.value) {
                    _hasCreatedMoreThanOneConvo.value = true
                    prefs.edit { putBoolean("hasCreatedMoreThanOneConvo", true) }
                }

                if (allConversations.isEmpty()) {
                    // Show Empty state if either:
                    // 1. We've completed the initial sync, OR
                    // 2. We've had conversations before (prevents showing Loading when all conversations expire)
                    if (isInitialSyncComplete || hasEverHadConversations) {
                        Log.d(TAG, "observeAllConversations: No conversations (syncComplete=$isInitialSyncComplete, hadConvos=$hasEverHadConversations), setting Empty state")
                        _uiState.value = ConversationsUiState.Empty
                    } else {
                        Log.d(TAG, "observeAllConversations: No conversations yet, sync still in progress, keeping Loading state")
                        _uiState.value = ConversationsUiState.Loading
                    }
                } else {
                    // Mark that we've had conversations so we don't show Loading spinner if they all expire
                    hasEverHadConversations = true

                    Log.d(
                        TAG,
                        "observeAllConversations: Setting Success state with ${allConversations.size} conversations"
                    )
                    allConversations.forEach { convo ->
                        Log.d(TAG, "  - ${convo.id}: ${convo.name ?: "Untitled"}")
                    }
                    _uiState.value = ConversationsUiState.Success(allConversations)
                }
            }
    }

    /**
     * Manual sync for pull-to-refresh or when user expects join requests.
     * Ensures clients are loaded and processes pending join requests immediately.
     */
    fun syncConversations() {
        viewModelScope.launch {
            Log.d(TAG, "Manual sync triggered")
            _uiState.value = ConversationsUiState.Syncing(
                (_uiState.value as? ConversationsUiState.Success)?.conversations ?: emptyList()
            )

            // Sync conversations for all inboxes
            val allInboxIds = sessionManager.getAllInboxIds()
            for (inboxId in allInboxIds) {
                // Ensure client is loaded before syncing
                sessionManager.ensureClientLoaded(inboxId).fold(
                    onSuccess = {
                        conversationRepository.syncConversations(inboxId).fold(
                            onSuccess = {
                                Log.d(TAG, "Manual sync completed for inbox: $inboxId")
                            },
                            onFailure = { error ->
                                Log.e(TAG, "Manual sync failed for inbox: $inboxId", error)
                            }
                        )
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to load client for manual sync: $inboxId", error)
                    }
                )
            }
        }
    }

    fun selectConversation(conversationId: String) {
        _selectedConversationId.value = conversationId
        sessionManager.setActiveConversation(conversationId)
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            // Set consent to DENIED
            conversationRepository.updateConsent(conversationId, ConsentState.DENIED)
            Log.d(TAG, "Conversation deleted: $conversationId")

            // Clean up the inbox for this conversation since we only use one inbox per conversation
            conversationRepository.cleanupInboxForConversation(conversationId)
        }
    }

    /**
     * Periodic sync fallback for reliability (catch missed join requests if streams fail).
     * - Primary: XMTP streams handle join requests instantly
     * - Fallback: This catches anything streams might miss (network issues, stream failures)
     * - Adaptive timing:
     *   - Group creators: Every 30 seconds (waiting for join requests - fast response)
     *   - Regular members: Every 5 minutes (battery-friendly)
     */
    private fun startPeriodicFallbackSync() {
        viewModelScope.launch {
            // Start immediately for first check
            delay(10_000) // First sync after 10 seconds (let initial load complete)

            while (true) {
                // Only sync if we have an active session
                val sessionState = sessionManager.sessionState.value
                if (sessionState is SessionState.Active) {
                    var isCreatorOfAnyGroup = false

                    try {
                        val allInboxIds = sessionManager.getAllInboxIds()
                        for (inboxId in allInboxIds) {
                            // Ensure client is loaded before attempting sync
                            sessionManager.ensureClientLoaded(inboxId).fold(
                                onSuccess = {
                                    // Check if this inbox is a creator of any group
                                    val client = xmtpClientManager.getClient(inboxId)
                                    if (client != null) {
                                        try {
                                            val groups = client.conversations.listGroups()
                                            val isCreator = groups.any { group ->
                                                try {
                                                    group.isCreator()
                                                } catch (e: Exception) {
                                                    false
                                                }
                                            }
                                            if (isCreator) {
                                                isCreatorOfAnyGroup = true
                                                Log.d(TAG, "Inbox $inboxId is creator of at least one group")
                                            }
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Failed to check creator status for inbox: $inboxId", e)
                                        }
                                    }

                                    // Just sync conversations (includes join request processing)
                                    // Don't sync messages - streams handle that
                                    conversationRepository.syncConversations(inboxId).fold(
                                        onSuccess = {
                                            Log.d(TAG, "Fallback sync completed for inbox: $inboxId")
                                        },
                                        onFailure = { error ->
                                            Log.e(TAG, "Fallback sync failed for inbox: $inboxId", error)
                                        }
                                    )
                                },
                                onFailure = { error ->
                                    Log.e(TAG, "Failed to load client for fallback sync: $inboxId", error)
                                }
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during fallback sync", e)
                    }

                    // Adaptive delay based on creator status
                    val syncInterval = if (isCreatorOfAnyGroup) {
                        30_000L // 30 seconds for creators (fast join request processing)
                    } else {
                        300_000L // 5 minutes for regular members (battery-friendly)
                    }

                    Log.d(TAG, "Next fallback sync in ${syncInterval/1000}s (isCreator=$isCreatorOfAnyGroup)")
                    delay(syncInterval)
                } else {
                    // No active session, check again in 30 seconds
                    delay(30_000)
                }
            }
        }
    }


    /**
     * Check for expired conversations with adaptive timing for battery efficiency.
     * - Checks every 5 seconds when no conversations are expiring soon (battery-friendly)
     * - Checks every 1 second when expiration is within 60 seconds (accurate)
     * - Skips checks entirely when no conversations have expiresAt set
     */
    private fun startExpirationCheck() {
        viewModelScope.launch {
            var lastLogTime = 0L
            while (true) {
                // Get current UI state
                val currentState = _uiState.value
                val conversations = when (currentState) {
                    is ConversationsUiState.Success -> currentState.conversations
                    is ConversationsUiState.Syncing -> currentState.conversations
                    else -> emptyList()
                }

                val now = System.currentTimeMillis()

                // Find conversations with expiration times
                val conversationsWithExpiry = conversations.filter { it.expiresAt != null }

                if (conversationsWithExpiry.isEmpty()) {
                    // No conversations expiring - check infrequently (battery savings)
                    delay(10_000) // Check every 10 seconds
                    continue
                }

                // Find the soonest expiration time
                val nextExpirationTime = conversationsWithExpiry.minOfOrNull { it.expiresAt!! }
                val timeUntilNextExpiration = if (nextExpirationTime != null) {
                    nextExpirationTime - now
                } else {
                    Long.MAX_VALUE
                }

                // Adaptive delay based on how soon the next expiration is
                val checkInterval = when {
                    timeUntilNextExpiration <= 0 -> 0L // Already expired, process now
                    timeUntilNextExpiration < 60_000 -> 1_000L // Within 60 seconds - check every second
                    timeUntilNextExpiration < 300_000 -> 5_000L // Within 5 minutes - check every 5 seconds
                    else -> 30_000L // More than 5 minutes away - check every 30 seconds
                }

                // Log expiration status every 30 seconds (reduced logging)
                if (now - lastLogTime > 30_000) {
                    Log.d(TAG, "[EXPIRATION] ${conversationsWithExpiry.size} conversations with expiry, next in ${timeUntilNextExpiration/1000}s, checking every ${checkInterval/1000}s")
                    lastLogTime = now
                }

                // Check if any conversations have expired
                val hasExpired = conversations.any { conv ->
                    val expired = conv.expiresAt != null && conv.expiresAt!! <= now
                    if (expired) {
                        Log.d(TAG, "[EXPIRATION] Conversation ${conv.id.take(8)} HAS EXPIRED!")
                    }
                    expired
                }

                if (hasExpired) {
                    Log.d(TAG, "🔥 Detected expired conversation(s), deleting immediately...")
                    try {
                        conversationRepository.cleanupExpiredConversations()
                        Log.d(TAG, "✅ Expired conversations cleaned up successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to cleanup expired conversations", e)
                    }
                } else {
                    // No expiration yet, wait for the calculated interval
                    delay(checkInterval)
                }
            }
        }
    }
}

sealed class ConversationsUiState {
    object Loading : ConversationsUiState()
    object NoSession : ConversationsUiState()
    object Empty : ConversationsUiState()
    data class Success(val conversations: List<Conversation>) : ConversationsUiState()
    data class Syncing(val conversations: List<Conversation>) : ConversationsUiState()
    data class Error(val message: String) : ConversationsUiState()
}
