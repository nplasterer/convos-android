package com.naomiplasterer.convos.data.xmtp

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.xmtp.android.library.Client
import org.xmtp.android.library.ClientOptions
import org.xmtp.android.library.XMTPEnvironment
import org.xmtp.android.library.codecs.AttachmentCodec
import org.xmtp.android.library.codecs.ReactionCodec
import org.xmtp.android.library.codecs.ReplyCodec
import org.xmtp.android.library.messages.PrivateKeyBuilder
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "XMTPClientManager"

@Singleton
class XMTPClientManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keychainIdentityManager: KeychainIdentityManager
) {
    private val _clients = mutableMapOf<String, Client>()
    private val _activeInboxId = MutableStateFlow<String?>(null)

    init {
        registerCodecs()
    }

    private fun registerCodecs() {
        Client.register(codec = AttachmentCodec())
        Client.register(codec = ReactionCodec())
        Client.register(codec = ReplyCodec())
        Log.d(TAG, "Registered XMTP codecs: Attachment, Reaction, Reply")
    }

    fun getClient(inboxId: String): Client? {
        return _clients[inboxId]
    }

    /**
     * Get the raw secp256k1 private key data for an inbox.
     * This matches iOS: identity.keys.privateKey.secp256K1.bytes
     * Returns the 32-byte wallet private key used for cryptographic operations.
     */
    fun getPrivateKeyData(inboxId: String): ByteArray? {
        return try {
            val storedIdentity = keychainIdentityManager.loadIdentity(inboxId)
            if (storedIdentity == null) {
                Log.w(TAG, "No stored identity found for inbox: $inboxId")
                return null
            }

            // Parse the stored PrivateKey protobuf (same format as iOS)
            val privateKey = org.xmtp.android.library.messages.PrivateKey.parseFrom(storedIdentity.privateKeyData)

            // Extract the secp256k1 private key bytes (matching iOS: privateKey.secp256K1.bytes)
            val privateKeyBytes = when {
                privateKey.hasSecp256K1() -> {
                    val bytes = privateKey.secp256K1.bytes.toByteArray()
                    Log.d(TAG, "Retrieved secp256k1 private key for inbox $inboxId: ${bytes.size} bytes, first 4 bytes: ${bytes.take(4).joinToString(",") { "%02x".format(it) }}")
                    bytes
                }
                else -> {
                    Log.w(TAG, "Private key is not secp256k1 type for inbox: $inboxId")
                    null
                }
            }

            privateKeyBytes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get private key data for inbox: $inboxId", e)
            null
        }
    }

    suspend fun createClient(
        inboxId: String? = null,
        environment: XMTPEnvironment = XMTPEnvironment.PRODUCTION
    ): Result<Client> {
        return try {
            val client = if (inboxId != null) {
                Log.d(TAG, "Authorizing existing client for inbox: $inboxId")
                authorizeExistingClient(inboxId, environment)
            } else {
                Log.d(TAG, "Registering new client")
                registerNewClient(environment)
            }

            _clients[client.inboxId] = client
            _activeInboxId.value = client.inboxId
            Log.d(TAG, "Successfully created/authorized XMTP client for inbox: ${client.inboxId}")

            Result.success(client)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create XMTP client", e)
            Result.failure(e)
        }
    }

    private suspend fun registerNewClient(environment: XMTPEnvironment): Client {
        val wallet = keychainIdentityManager.generateNewIdentity()
        val dbKey = keychainIdentityManager.generateDatabaseKey()
        val options = buildClientOptions(dbKey, environment)

        Log.d(TAG, "Creating new XMTP client with random wallet")
        val client = Client.create(account = wallet, options = options)

        keychainIdentityManager.saveIdentity(client.inboxId, wallet, dbKey)
        Log.d(TAG, "New client created and saved. InboxId: ${client.inboxId}")

        return client
    }

    private suspend fun authorizeExistingClient(inboxId: String, environment: XMTPEnvironment): Client {
        val storedIdentity = keychainIdentityManager.loadIdentity(inboxId)
            ?: throw IllegalStateException("No stored identity found for inbox: $inboxId")

        val privateKey = org.xmtp.android.library.messages.PrivateKey.parseFrom(storedIdentity.privateKeyData)
        val wallet = PrivateKeyBuilder(privateKey)
        val options = buildClientOptions(storedIdentity.databaseKey, environment)

        Log.d(TAG, "Building XMTP client from stored identity")
        val client = Client.build(
            publicIdentity = wallet.publicIdentity,
            options = options,
            inboxId = inboxId
        )

        Log.d(TAG, "Client built from stored identity. InboxId: ${client.inboxId}")
        return client
    }

    private fun buildClientOptions(dbKey: ByteArray, environment: XMTPEnvironment): ClientOptions {
        return ClientOptions(
            api = ClientOptions.Api(env = environment, isSecure = true),
            appContext = context,
            dbEncryptionKey = dbKey
        )
    }

    fun removeClient(inboxId: String) {
        _clients.remove(inboxId)
        if (_activeInboxId.value == inboxId) {
            _activeInboxId.value = _clients.keys.firstOrNull()
        }
        Log.d(TAG, "Removed client for inbox: $inboxId")
    }

    fun clearAll() {
        _clients.clear()
        _activeInboxId.value = null
        Log.d(TAG, "Cleared all XMTP clients")
    }
}
