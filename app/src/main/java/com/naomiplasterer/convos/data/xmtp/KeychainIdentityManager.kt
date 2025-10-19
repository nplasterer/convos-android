package com.naomiplasterer.convos.data.xmtp

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.xmtp.android.library.messages.PrivateKeyBuilder
import timber.log.Timber
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

data class StoredIdentity(
    val inboxId: String,
    val privateKeyData: ByteArray,
    val databaseKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredIdentity) return false
        if (inboxId != other.inboxId) return false
        if (!privateKeyData.contentEquals(other.privateKeyData)) return false
        if (!databaseKey.contentEquals(other.databaseKey)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = inboxId.hashCode()
        result = 31 * result + privateKeyData.contentHashCode()
        result = 31 * result + databaseKey.contentHashCode()
        return result
    }
}

@Singleton
class KeychainIdentityManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "xmtp_identities",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun generateNewIdentity(): PrivateKeyBuilder {
        Timber.d("Generating new identity with random wallet")
        return PrivateKeyBuilder()
    }

    fun generateDatabaseKey(): ByteArray {
        Timber.d("Generating new 32-byte database encryption key")
        return SecureRandom().generateSeed(32)
    }

    fun saveIdentity(inboxId: String, privateKeyBuilder: PrivateKeyBuilder, databaseKey: ByteArray) {
        try {
            val privateKeyBytes = privateKeyBuilder.getPrivateKey().toByteArray()
            val encodedPrivateKey = Base64.encodeToString(privateKeyBytes, Base64.DEFAULT)
            val encodedDbKey = Base64.encodeToString(databaseKey, Base64.DEFAULT)

            sharedPreferences.edit().apply {
                putString("${PRIVATE_KEY_PREFIX}$inboxId", encodedPrivateKey)
                putString("${DB_KEY_PREFIX}$inboxId", encodedDbKey)
                apply()
            }

            Timber.d("Saved identity for inbox: $inboxId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save identity for inbox: $inboxId")
            throw e
        }
    }

    fun loadIdentity(inboxId: String): StoredIdentity? {
        return try {
            val encodedPrivateKey = sharedPreferences.getString("${PRIVATE_KEY_PREFIX}$inboxId", null)
            val encodedDbKey = sharedPreferences.getString("${DB_KEY_PREFIX}$inboxId", null)

            if (encodedPrivateKey == null || encodedDbKey == null) {
                Timber.d("No identity found for inbox: $inboxId")
                return null
            }

            val privateKeyData = Base64.decode(encodedPrivateKey, Base64.DEFAULT)
            val databaseKey = Base64.decode(encodedDbKey, Base64.DEFAULT)

            Timber.d("Loaded identity for inbox: $inboxId")
            StoredIdentity(inboxId, privateKeyData, databaseKey)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load identity for inbox: $inboxId")
            null
        }
    }

    fun deleteIdentity(inboxId: String) {
        sharedPreferences.edit().apply {
            remove("${PRIVATE_KEY_PREFIX}$inboxId")
            remove("${DB_KEY_PREFIX}$inboxId")
            apply()
        }
        Timber.d("Deleted identity for inbox: $inboxId")
    }

    fun getAllInboxIds(): List<String> {
        val allKeys = sharedPreferences.all.keys
        return allKeys
            .filter { it.startsWith(PRIVATE_KEY_PREFIX) }
            .map { it.removePrefix(PRIVATE_KEY_PREFIX) }
            .also { Timber.d("Found ${it.size} stored identities") }
    }

    fun clearAll() {
        sharedPreferences.edit().clear().apply()
        Timber.d("Cleared all stored identities")
    }

    companion object {
        private const val PRIVATE_KEY_PREFIX = "private_key_"
        private const val DB_KEY_PREFIX = "db_key_"
    }
}
