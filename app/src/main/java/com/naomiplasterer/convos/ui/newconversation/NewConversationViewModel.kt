package com.naomiplasterer.convos.ui.newconversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naomiplasterer.convos.data.repository.ConversationRepository
import com.naomiplasterer.convos.data.session.SessionManager
import com.naomiplasterer.convos.data.xmtp.XMTPClientManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xmtp.android.library.XMTPEnvironment
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class NewConversationViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val sessionManager: SessionManager,
    private val xmtpClientManager: XMTPClientManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<NewConversationUiState>(NewConversationUiState.Idle)
    val uiState: StateFlow<NewConversationUiState> = _uiState.asStateFlow()

    private val _inviteCode = MutableStateFlow("")
    val inviteCode: StateFlow<String> = _inviteCode.asStateFlow()

    private val _mode = MutableStateFlow(NewConversationMode.SCAN)
    val mode: StateFlow<NewConversationMode> = _mode.asStateFlow()

    private val _createdConversationId = MutableStateFlow<String?>(null)
    val createdConversationId: StateFlow<String?> = _createdConversationId.asStateFlow()

    init {
        if (_mode.value == NewConversationMode.CREATE) {
            createNewConversation()
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
                Timber.d("Creating new conversation")

                val activeClient = xmtpClientManager.getActiveClient()
                    ?: run {
                        Timber.d("No active client found, creating new client")
                        xmtpClientManager.createClient(
                            address = "random",
                            environment = XMTPEnvironment.DEV
                        ).getOrThrow()
                    }

                val inboxId = activeClient.inboxId
                Timber.d("Active client ready with inboxId: $inboxId")

                val group = activeClient.conversations.newGroup(emptyList())
                val conversationId = group.id

                Timber.d("Created new conversation: $conversationId")

                conversationRepository.syncConversations(inboxId).onFailure { error ->
                    Timber.e(error, "Failed to sync conversations after creation")
                }

                _createdConversationId.value = conversationId
                _uiState.value = NewConversationUiState.Success(conversationId)

            } catch (e: Exception) {
                Timber.e(e, "Failed to create conversation")
                _uiState.value = NewConversationUiState.Error(e.message ?: "Failed to create conversation")
            }
        }
    }

    fun updateInviteCode(code: String) {
        _inviteCode.value = code
    }

    fun onQRCodeScanned(code: String) {
        Timber.d("QR code scanned: $code")
        val inviteCode = extractInviteCode(code)
        if (inviteCode != null) {
            _inviteCode.value = inviteCode
            joinConversation(inviteCode)
        } else {
            _uiState.value = NewConversationUiState.Error("Invalid invite code format")
        }
    }

    fun joinConversation(code: String = _inviteCode.value) {
        if (code.isBlank()) {
            _uiState.value = NewConversationUiState.Error("Please enter an invite code")
            return
        }

        viewModelScope.launch {
            _uiState.value = NewConversationUiState.Joining

            try {
                Timber.d("Joining conversation with invite code: $code")

                val activeClient = xmtpClientManager.getActiveClient()
                    ?: run {
                        Timber.d("No active client found, creating new client")
                        xmtpClientManager.createClient(
                            address = "random",
                            environment = XMTPEnvironment.DEV
                        ).getOrThrow()
                    }

                Timber.d("Active client ready with inboxId: ${activeClient.inboxId}")

                _uiState.value = NewConversationUiState.Error("Invite code processing not yet implemented")

            } catch (e: Exception) {
                Timber.e(e, "Failed to join conversation")
                _uiState.value = NewConversationUiState.Error(e.message ?: "Failed to join conversation")
            }
        }
    }

    private fun extractInviteCode(qrCodeData: String): String? {
        return when {
            qrCodeData.startsWith("convos://i/") -> {
                qrCodeData.removePrefix("convos://i/")
            }
            qrCodeData.startsWith("https://convos.app/i/") -> {
                qrCodeData.removePrefix("https://convos.app/i/")
            }
            qrCodeData.matches(Regex("^[a-zA-Z0-9-_]+$")) -> {
                qrCodeData
            }
            else -> null
        }
    }

    fun resetState() {
        _uiState.value = NewConversationUiState.Idle
        _inviteCode.value = ""
    }
}

sealed class NewConversationUiState {
    object Idle : NewConversationUiState()
    object Creating : NewConversationUiState()
    object Joining : NewConversationUiState()
    data class Success(val conversationId: String) : NewConversationUiState()
    data class Error(val message: String) : NewConversationUiState()
}

enum class NewConversationMode {
    CREATE,
    SCAN,
    MANUAL
}
