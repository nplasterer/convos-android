package com.naomiplasterer.convos.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naomiplasterer.convos.data.repository.ConversationRepository
import com.naomiplasterer.convos.data.session.SessionManager
import com.naomiplasterer.convos.data.session.SessionState
import com.naomiplasterer.convos.domain.model.Conversation
import com.naomiplasterer.convos.domain.model.ConsentState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<ConversationsUiState>(ConversationsUiState.Loading)
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()

    private val _selectedConversationId = MutableStateFlow<String?>(null)
    val selectedConversationId: StateFlow<String?> = _selectedConversationId.asStateFlow()

    init {
        observeSession()
    }

    private fun observeSession() {
        viewModelScope.launch {
            sessionManager.sessionState.collectLatest { sessionState ->
                when (sessionState) {
                    is SessionState.Active -> {
                        loadConversations(sessionState.inboxId)
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

    private fun loadConversations(inboxId: String) {
        viewModelScope.launch {
            try {
                conversationRepository.getConversations(inboxId).collectLatest { conversations ->
                    if (conversations.isEmpty()) {
                        _uiState.value = ConversationsUiState.Empty
                    } else {
                        _uiState.value = ConversationsUiState.Success(conversations)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load conversations")
                _uiState.value = ConversationsUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun syncConversations() {
        viewModelScope.launch {
            val inboxId = sessionManager.getCurrentInboxId() ?: return@launch

            _uiState.value = ConversationsUiState.Syncing(
                (_uiState.value as? ConversationsUiState.Success)?.conversations ?: emptyList()
            )

            conversationRepository.syncConversations(inboxId).fold(
                onSuccess = {
                    Timber.d("Conversations synced successfully")
                },
                onFailure = { error ->
                    Timber.e(error, "Failed to sync conversations")
                    _uiState.value = ConversationsUiState.Error(error.message ?: "Sync failed")
                }
            )
        }
    }

    fun selectConversation(conversationId: String) {
        _selectedConversationId.value = conversationId
        sessionManager.setActiveConversation(conversationId)
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.updateConsent(conversationId, ConsentState.DENIED)
            Timber.d("Conversation deleted: $conversationId")
        }
    }

    fun pinConversation(conversationId: String, isPinned: Boolean) {
        viewModelScope.launch {
            conversationRepository.updatePinned(conversationId, isPinned)
        }
    }

    fun markAsRead(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.updateUnread(conversationId, false)
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
