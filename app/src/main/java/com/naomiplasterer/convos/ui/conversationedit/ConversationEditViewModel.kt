package com.naomiplasterer.convos.ui.conversationedit

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naomiplasterer.convos.data.repository.ConversationRepository
import com.naomiplasterer.convos.data.session.SessionManager
import com.naomiplasterer.convos.data.session.SessionState
import com.naomiplasterer.convos.domain.model.Conversation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import android.util.Log
import javax.inject.Inject

private const val TAG = "ConversationEditViewModel"

@HiltViewModel
class ConversationEditViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val sessionManager: SessionManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val conversationId: String = checkNotNull(savedStateHandle["conversationId"])

    private val _uiState = MutableStateFlow<ConversationEditUiState>(ConversationEditUiState.Loading)
    val uiState: StateFlow<ConversationEditUiState> = _uiState.asStateFlow()

    private val _conversationName = MutableStateFlow("")
    val conversationName: StateFlow<String> = _conversationName.asStateFlow()

    private val _conversationImageUri = MutableStateFlow<String?>(null)
    val conversationImageUri: StateFlow<String?> = _conversationImageUri.asStateFlow()

    companion object {
        const val MAX_NAME_LENGTH = 100
    }

    init {
        loadConversation()
    }

    private fun loadConversation() {
        viewModelScope.launch {
            try {
                val sessionState = sessionManager.sessionState.value
                if (sessionState !is SessionState.Active) {
                    _uiState.value = ConversationEditUiState.Error("No active session")
                    return@launch
                }

                val conversation = conversationRepository
                    .getConversations(sessionState.inboxId)
                    .firstOrNull()
                    ?.find { it.id == conversationId }

                if (conversation != null) {
                    _conversationName.value = conversation.name ?: ""
                    _conversationImageUri.value = conversation.imageUrl
                    _uiState.value = ConversationEditUiState.Success(conversation)
                } else {
                    _uiState.value = ConversationEditUiState.Error("Conversation not found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load conversation", e)
                _uiState.value = ConversationEditUiState.Error(e.message ?: "Failed to load conversation")
            }
        }
    }

    fun updateConversationName(name: String) {
        if (name.length <= MAX_NAME_LENGTH) {
            _conversationName.value = name
        }
    }

    fun updateConversationImage(uri: Uri?) {
        _conversationImageUri.value = uri?.toString()
    }

    fun saveConversation() {
        viewModelScope.launch {
            try {
                val sessionState = sessionManager.sessionState.value
                if (sessionState !is SessionState.Active) {
                    _uiState.value = ConversationEditUiState.Error("No active session")
                    return@launch
                }

                conversationRepository.updateConversationDetails(
                    inboxId = sessionState.inboxId,
                    conversationId = conversationId,
                    name = _conversationName.value.ifBlank { null },
                    description = null,
                    imageUrl = _conversationImageUri.value
                ).fold(
                    onSuccess = {
                        Log.d(TAG, "Conversation details saved successfully")
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to save conversation details", error)
                        _uiState.value = ConversationEditUiState.Error(error.message ?: "Failed to save conversation")
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save conversation details", e)
                _uiState.value = ConversationEditUiState.Error(e.message ?: "Failed to save conversation")
            }
        }
    }
}

sealed class ConversationEditUiState {
    object Loading : ConversationEditUiState()
    data class Success(val conversation: Conversation) : ConversationEditUiState()
    data class Error(val message: String) : ConversationEditUiState()
}
