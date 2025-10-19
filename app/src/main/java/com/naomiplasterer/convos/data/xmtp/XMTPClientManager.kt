package com.naomiplasterer.convos.data.xmtp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xmtp.android.library.Client
import org.xmtp.android.library.ClientOptions
import org.xmtp.android.library.XMTPEnvironment
import org.xmtp.android.library.messages.PrivateKeyBuilder
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class XMTPClientManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keychainIdentityManager: KeychainIdentityManager
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
            val client = if (inboxId != null) {
                Timber.d("Authorizing existing client for inbox: $inboxId")
                authorizeExistingClient(inboxId, environment)
            } else {
                Timber.d("Registering new client")
                registerNewClient(environment)
            }

            _clients[client.inboxId] = client
            _activeInboxId.value = client.inboxId
            Timber.d("Successfully created/authorized XMTP client for inbox: ${client.inboxId}")

            Result.success(client)
        } catch (e: Exception) {
            Timber.e(e, "Failed to create XMTP client")
            Result.failure(e)
        }
    }

    private suspend fun registerNewClient(environment: XMTPEnvironment): Client {
        val wallet = keychainIdentityManager.generateNewIdentity()
        val dbKey = keychainIdentityManager.generateDatabaseKey()
        val options = buildClientOptions(dbKey, environment)

        Timber.d("Creating new XMTP client with random wallet")
        val client = Client.create(account = wallet, options = options)

        keychainIdentityManager.saveIdentity(client.inboxId, wallet, dbKey)
        Timber.d("New client created and saved. InboxId: ${client.inboxId}")

        return client
    }

    private suspend fun authorizeExistingClient(inboxId: String, environment: XMTPEnvironment): Client {
        val storedIdentity = keychainIdentityManager.loadIdentity(inboxId)
            ?: throw IllegalStateException("No stored identity found for inbox: $inboxId")

        val privateKey = org.xmtp.android.library.messages.PrivateKey.parseFrom(storedIdentity.privateKeyData)
        val wallet = PrivateKeyBuilder(privateKey)
        val options = buildClientOptions(storedIdentity.databaseKey, environment)

        Timber.d("Building XMTP client from stored identity")
        val client = Client.build(
            publicIdentity = wallet.publicIdentity,
            options = options,
            inboxId = inboxId
        )

        Timber.d("Client built from stored identity. InboxId: ${client.inboxId}")
        return client
    }

    private fun buildClientOptions(dbKey: ByteArray, environment: XMTPEnvironment): ClientOptions {
        return ClientOptions(
            api = ClientOptions.Api(env = environment, isSecure = true),
            appContext = context,
            dbEncryptionKey = dbKey
        )
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
