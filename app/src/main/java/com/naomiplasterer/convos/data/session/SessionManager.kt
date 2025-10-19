package com.naomiplasterer.convos.data.session

import com.naomiplasterer.convos.data.local.dao.InboxDao
import com.naomiplasterer.convos.data.local.entity.InboxEntity
import com.naomiplasterer.convos.data.xmtp.XMTPClientManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xmtp.android.library.XMTPEnvironment
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionManager @Inject constructor(
    private val xmtpClientManager: XMTPClientManager,
    private val inboxDao: InboxDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.NoSession)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    init {
        scope.launch {
            val inboxes = inboxDao.getAllInboxes()
            inboxes.collect { inboxList ->
                if (inboxList.isNotEmpty() && _sessionState.value is SessionState.NoSession) {
                    val firstInbox = inboxList.first()
                    Timber.d("Found existing inbox, auto-selecting: ${firstInbox.inboxId}")
                }
            }
        }
    }

    suspend fun createSession(
        address: String,
        environment: XMTPEnvironment = XMTPEnvironment.DEV
    ): Result<String> {
        _sessionState.value = SessionState.Creating

        return try {
            val clientResult = xmtpClientManager.createClient(address, environment = environment)

            clientResult.fold(
                onSuccess = { client ->
                    val inbox = InboxEntity(
                        inboxId = client.inboxId,
                        address = address,
                        createdAt = System.currentTimeMillis()
                    )
                    inboxDao.insert(inbox)

                    _sessionState.value = SessionState.Active(
                        inboxId = client.inboxId,
                        address = address
                    )

                    Timber.d("Session created for inbox: ${client.inboxId}")
                    Result.success(client.inboxId)
                },
                onFailure = { error ->
                    _sessionState.value = SessionState.Error(error.message ?: "Unknown error")
                    Timber.e(error, "Failed to create session")
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            _sessionState.value = SessionState.Error(e.message ?: "Unknown error")
            Timber.e(e, "Exception creating session")
            Result.failure(e)
        }
    }

    suspend fun switchSession(inboxId: String): Result<Unit> {
        return try {
            val inbox = inboxDao.getInbox(inboxId)
            if (inbox == null) {
                return Result.failure(IllegalArgumentException("Inbox not found: $inboxId"))
            }

            val client = xmtpClientManager.getClient(inboxId)
            if (client == null) {
                val recreateResult = xmtpClientManager.createClient(
                    address = inbox.address,
                    inboxId = inbox.inboxId
                )
                if (recreateResult.isFailure) {
                    return Result.failure(recreateResult.exceptionOrNull()!!)
                }
            }

            xmtpClientManager.setActiveInbox(inboxId)
            _sessionState.value = SessionState.Active(
                inboxId = inboxId,
                address = inbox.address
            )

            Timber.d("Switched to session: $inboxId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to switch session")
            Result.failure(e)
        }
    }

    suspend fun deleteSession(inboxId: String) {
        try {
            val inbox = inboxDao.getInbox(inboxId)
            if (inbox != null) {
                inboxDao.delete(inbox)
                xmtpClientManager.removeClient(inboxId)

                if ((_sessionState.value as? SessionState.Active)?.inboxId == inboxId) {
                    _sessionState.value = SessionState.NoSession
                }

                Timber.d("Deleted session: $inboxId")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete session")
        }
    }

    suspend fun deleteAllSessions() {
        try {
            inboxDao.deleteAll()
            xmtpClientManager.clearAll()
            _sessionState.value = SessionState.NoSession
            _activeConversationId.value = null

            Timber.d("Deleted all sessions")
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete all sessions")
        }
    }

    fun setActiveConversation(conversationId: String?) {
        _activeConversationId.value = conversationId
    }

    fun getCurrentInboxId(): String? {
        return (_sessionState.value as? SessionState.Active)?.inboxId
    }

    fun isSessionActive(): Boolean {
        return _sessionState.value is SessionState.Active
    }
}

sealed class SessionState {
    object NoSession : SessionState()
    object Creating : SessionState()
    data class Active(val inboxId: String, val address: String) : SessionState()
    data class Error(val message: String) : SessionState()
}
