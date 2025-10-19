package com.naomiplasterer.convos.ui.conversation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naomiplasterer.convos.data.repository.ConversationRepository
import com.naomiplasterer.convos.data.repository.MessageRepository
import com.naomiplasterer.convos.data.repository.ReactionRepository
import com.naomiplasterer.convos.data.session.SessionManager
import com.naomiplasterer.convos.domain.model.Conversation
import com.naomiplasterer.convos.domain.model.Message
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val reactionRepository: ReactionRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val conversationId: String = checkNotNull(savedStateHandle["conversationId"])

    private val _uiState = MutableStateFlow<ConversationUiState>(ConversationUiState.Loading)
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    init {
        loadConversation()
        loadMessages()
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
        val currentState = _uiState.value
        _uiState.value = when (currentState) {
            is ConversationUiState.Success -> currentState.copy(conversation = conversation)
            else -> ConversationUiState.Success(conversation = conversation, messages = emptyList())
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

        viewModelScope.launch {
            _isSending.value = true

            val inboxId = sessionManager.getCurrentInboxId()
            if (inboxId == null) {
                Timber.e("Cannot send message: no active inbox")
                _isSending.value = false
                return@launch
            }

            messageRepository.sendMessage(
                inboxId = inboxId,
                conversationId = conversationId,
                text = text
            ).fold(
                onSuccess = {
                    _messageText.value = ""
                    Timber.d("Message sent successfully")
                },
                onFailure = { error ->
                    Timber.e(error, "Failed to send message")
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
                    Timber.d("Messages synced successfully")
                },
                onFailure = { error ->
                    Timber.e(error, "Failed to sync messages")
                }
            )
        }
    }

    fun markAsRead() {
        viewModelScope.launch {
            conversationRepository.updateUnread(conversationId, false)
        }
    }

    fun addReaction(messageId: String, emoji: String) {
        viewModelScope.launch {
            val inboxId = sessionManager.getCurrentInboxId() ?: return@launch

            reactionRepository.addReaction(
                messageId = messageId,
                senderInboxId = inboxId,
                emoji = emoji
            ).fold(
                onSuccess = {
                    Timber.d("Reaction added successfully")
                },
                onFailure = { error ->
                    Timber.e(error, "Failed to add reaction")
                }
            )
        }
    }

    fun removeReaction(reactionId: String) {
        viewModelScope.launch {
            reactionRepository.removeReaction(reactionId).fold(
                onSuccess = {
                    Timber.d("Reaction removed successfully")
                },
                onFailure = { error ->
                    Timber.e(error, "Failed to remove reaction")
                }
            )
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
