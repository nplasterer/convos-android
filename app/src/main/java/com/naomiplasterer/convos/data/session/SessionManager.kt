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
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SessionManager"

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
            checkForExistingInboxes()
        }
    }

    /**
     * Check if there are existing inboxes but don't load clients yet.
     * Clients will be lazily loaded when needed (e.g., when viewing conversations).
     */
    private suspend fun checkForExistingInboxes() {
        try {
            val inboxList = inboxDao.getAllInboxesList()
            Log.d(TAG, "Found ${inboxList.size} existing inboxes")

            if (inboxList.isEmpty()) {
                Log.d(TAG, "No existing inboxes found")
                _sessionState.value = SessionState.NoSession
            } else {
                // Set first inbox as active but don't load clients yet
                val firstInbox = inboxList.first()
                _sessionState.value = SessionState.Active(
                    inboxId = firstInbox.inboxId,
                    address = firstInbox.address
                )
                Log.d(TAG, "Set active inbox: ${firstInbox.inboxId} (client will be loaded when needed)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for existing inboxes", e)
            _sessionState.value = SessionState.Error(e.message ?: "Failed to check inboxes")
        }
    }

    /**
     * Ensure client is loaded for the given inbox ID.
     * Loads lazily if not already loaded.
     */
    suspend fun ensureClientLoaded(inboxId: String): Result<Unit> {
        return try {
            // Check if client is already loaded
            if (xmtpClientManager.getClient(inboxId) != null) {
                Log.d(TAG, "Client already loaded for inbox: $inboxId")
                return Result.success(Unit)
            }

            // Load the client
            val inbox = inboxDao.getInbox(inboxId)
                ?: return Result.failure(IllegalStateException("Inbox not found: $inboxId"))

            Log.d(TAG, "Lazy loading client for inbox: $inboxId")
            val clientResult = xmtpClientManager.createClient(
                address = inbox.address,
                inboxId = inbox.inboxId
            )

            clientResult.fold(
                onSuccess = {
                    Log.d(TAG, "Client loaded successfully for inbox: $inboxId")
                    Result.success(Unit)
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load client for inbox: $inboxId", error)
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading client for inbox: $inboxId", e)
            Result.failure(e)
        }
    }

    suspend fun createSession(
        address: String,
        environment: XMTPEnvironment = XMTPEnvironment.PRODUCTION
    ): Result<String> {
        _sessionState.value = SessionState.Creating

        return try {
            val clientResult = xmtpClientManager.createClient(address, environment = environment)

            clientResult.fold(
                onSuccess = { client ->
                    val inbox = InboxEntity(
                        inboxId = client.inboxId,
                        clientId = client.installationId,
                        address = address,
                        createdAt = System.currentTimeMillis()
                    )
                    inboxDao.insert(inbox)

                    _sessionState.value = SessionState.Active(
                        inboxId = client.inboxId,
                        address = address
                    )

                    Log.d(TAG, "Session created for inbox: ${client.inboxId}")
                    Result.success(client.inboxId)
                },
                onFailure = { error ->
                    _sessionState.value = SessionState.Error(error.message ?: "Unknown error")
                    Log.e(TAG, "Failed to create session", error)
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            _sessionState.value = SessionState.Error(e.message ?: "Unknown error")
            Log.e(TAG, "Exception creating session", e)
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

            Log.d(TAG, "Switched to session: $inboxId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch session", e)
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

                Log.d(TAG, "Deleted session: $inboxId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete session", e)
        }
    }

    suspend fun deleteAllSessions() {
        try {
            inboxDao.deleteAll()
            xmtpClientManager.clearAll()
            _sessionState.value = SessionState.NoSession
            _activeConversationId.value = null

            Log.d(TAG, "Deleted all sessions")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete all sessions", e)
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

    suspend fun getAllInboxIds(): List<String> {
        return inboxDao.getAllInboxesList().map { it.inboxId }
    }
}

sealed class SessionState {
    object NoSession : SessionState()
    object Creating : SessionState()
    data class Active(val inboxId: String, val address: String) : SessionState()
    data class Error(val message: String) : SessionState()
}
