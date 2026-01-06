package com.naomiplasterer.convos.ui.conversations

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naomiplasterer.convos.data.repository.ConversationRepository
import com.naomiplasterer.convos.data.session.SessionManager
import com.naomiplasterer.convos.data.session.SessionState
import com.naomiplasterer.convos.domain.model.Conversation
import com.naomiplasterer.convos.domain.model.ConsentState
import com.naomiplasterer.convos.domain.model.meaningfullyEquals
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.util.Log
import javax.inject.Inject
import androidx.core.content.edit

private const val TAG = "ConversationsViewModel"

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val sessionManager: SessionManager,
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


    init {
        observeSession()
        startPeriodicSync()
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

            // Reset sync flag when starting a new load
            isInitialSyncComplete = false

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

                if (allConversations.size > 1 && !_hasCreatedMoreThanOneConvo.value) {
                    _hasCreatedMoreThanOneConvo.value = true
                    prefs.edit { putBoolean("hasCreatedMoreThanOneConvo", true) }
                }

                if (allConversations.isEmpty()) {
                    // Only show Empty (CTA) if we've completed the initial sync
                    // Otherwise keep showing Loading spinner
                    if (isInitialSyncComplete) {
                        Log.d(TAG, "observeAllConversations: No conversations after sync complete, setting Empty state")
                        _uiState.value = ConversationsUiState.Empty
                    } else {
                        Log.d(TAG, "observeAllConversations: No conversations yet, but sync still in progress, keeping Loading state")
                        _uiState.value = ConversationsUiState.Loading
                    }
                } else {
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

    fun syncConversations() {
        viewModelScope.launch {
            _uiState.value = ConversationsUiState.Syncing(
                (_uiState.value as? ConversationsUiState.Success)?.conversations ?: emptyList()
            )

            // Sync conversations for all inboxes
            val allInboxIds = sessionManager.getAllInboxIds()
            for (inboxId in allInboxIds) {
                conversationRepository.syncConversations(inboxId).fold(
                    onSuccess = {
                        Log.d(TAG, "Conversations synced successfully for inbox: $inboxId")
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to sync conversations for inbox: $inboxId", error)
                        // Continue with other inboxes even if one fails
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
     * Start periodic syncing as a fallback for real-time streams.
     * Primary detection happens via message/conversation streams (instant).
     * This provides a safety net in case streams fail, processing within 10 seconds.
     */
    private fun startPeriodicSync() {
        viewModelScope.launch {
            while (true) {
                delay(10_000) // Sync every 10 seconds as fallback (primary detection via real-time streams)

                // Only sync if we have an active session
                val sessionState = sessionManager.sessionState.value
                if (sessionState is SessionState.Active) {
                    try {
                        val allInboxIds = sessionManager.getAllInboxIds()
                        for (inboxId in allInboxIds) {
                            conversationRepository.syncConversations(inboxId).fold(
                                onSuccess = {
                                    Log.d(TAG, "Periodic sync completed for inbox: $inboxId")
                                },
                                onFailure = { error ->
                                    Log.e(TAG, "Periodic sync failed for inbox: $inboxId", error)
                                }
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during periodic sync", e)
                    }
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
