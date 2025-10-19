package com.naomiplasterer.convos.data.xmtp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xmtp.android.library.Client
import org.xmtp.android.library.ClientOptions
import org.xmtp.android.library.XMTPEnvironment
import org.xmtp.android.library.SigningKey
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class XMTPClientManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionKeyManager: EncryptionKeyManager
) {
    private val _clients = mutableMapOf<String, Client>()
    private val _activeInboxId = MutableStateFlow<String?>(null)
    val activeInboxId: StateFlow<String?> = _activeInboxId.asStateFlow()

    fun getClient(inboxId: String): Client? {
        return _clients[inboxId]
    }

    fun getActiveClient(): Client? {
        return _activeInboxId.value?.let { _clients[it] }
    }

    suspend fun createClient(
        address: String,
        inboxId: String? = null,
        environment: XMTPEnvironment = XMTPEnvironment.DEV
    ): Result<Client> {
        return try {
            Timber.d("XMTP client creation requires wallet integration - returning placeholder error")
            Result.failure(IllegalStateException("Wallet integration required for XMTP client creation"))
        } catch (e: Exception) {
            Timber.e(e, "Failed to create XMTP client")
            Result.failure(e)
        }
    }

    fun setActiveInbox(inboxId: String) {
        if (_clients.containsKey(inboxId)) {
            _activeInboxId.value = inboxId
            Timber.d("Set active inbox: $inboxId")
        } else {
            Timber.w("Attempted to set non-existent inbox as active: $inboxId")
        }
    }

    fun removeClient(inboxId: String) {
        _clients.remove(inboxId)
        if (_activeInboxId.value == inboxId) {
            _activeInboxId.value = _clients.keys.firstOrNull()
        }
        Timber.d("Removed client for inbox: $inboxId")
    }

    fun clearAll() {
        _clients.clear()
        _activeInboxId.value = null
        Timber.d("Cleared all XMTP clients")
    }

    fun hasActiveClient(): Boolean = _activeInboxId.value != null && _clients.isNotEmpty()

    fun getAllInboxIds(): List<String> = _clients.keys.toList()
}
