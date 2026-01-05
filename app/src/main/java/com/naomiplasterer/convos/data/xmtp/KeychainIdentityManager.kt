package com.naomiplasterer.convos.data.xmtp

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.xmtp.android.library.messages.PrivateKeyBuilder
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KeychainIdentityManager"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "xmtp_identities_key"
private const val GCM_IV_LENGTH = 12
private const val GCM_TAG_LENGTH = 128

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
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        "xmtp_identities_v2",
        Context.MODE_PRIVATE
    )

    // Legacy encrypted preferences for migration
    @Suppress("DEPRECATION")
    private val legacyPreferences: SharedPreferences? by lazy {
        try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                "xmtp_identities",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create legacy encrypted preferences, migration not available", e)
            null
        }
    }

    init {
        // Ensure encryption key exists in AndroidKeyStore
        getOrCreateKey()
    }

    /**
     * Gets existing key from AndroidKeyStore or creates a new one if it doesn't exist.
     */
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        // Check if key already exists
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }

        // Create new key
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )

        return keyGenerator.generateKey()
    }

    /**
     * Encrypts data using AES/GCM with key from AndroidKeyStore.
     * Returns base64-encoded string in format: "iv:encryptedData"
     */
    private fun encrypt(data: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val iv = cipher.iv
        val encryptedData = cipher.doFinal(data)

        // Store IV and encrypted data together
        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val encryptedBase64 = Base64.encodeToString(encryptedData, Base64.NO_WRAP)

        return "$ivBase64:$encryptedBase64"
    }

    /**
     * Decrypts data that was encrypted with encrypt().
     * Expects format: "iv:encryptedData"
     */
    private fun decrypt(encryptedString: String): ByteArray {
        val parts = encryptedString.split(":")
        if (parts.size != 2) {
            throw IllegalArgumentException("Invalid encrypted data format")
        }

        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encryptedData = Base64.decode(parts[1], Base64.NO_WRAP)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))

        return cipher.doFinal(encryptedData)
    }

    fun generateNewIdentity(): PrivateKeyBuilder {
        Log.d(TAG, "Generating new identity with random wallet")
        return PrivateKeyBuilder()
    }

    fun generateDatabaseKey(): ByteArray {
        Log.d(TAG, "Generating new 32-byte database encryption key")
        return SecureRandom().generateSeed(32)
    }

    fun saveIdentity(
        inboxId: String,
        privateKeyBuilder: PrivateKeyBuilder,
        databaseKey: ByteArray
    ) {
        try {
            val privateKeyBytes = privateKeyBuilder.getPrivateKey().toByteArray()

            // Encrypt both keys using AndroidKeyStore
            val encryptedPrivateKey = encrypt(privateKeyBytes)
            val encryptedDbKey = encrypt(databaseKey)

            sharedPreferences.edit().apply {
                putString("${PRIVATE_KEY_PREFIX}$inboxId", encryptedPrivateKey)
                putString("${DB_KEY_PREFIX}$inboxId", encryptedDbKey)
                apply()
            }

            Log.d(TAG, "Saved encrypted identity for inbox: $inboxId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save identity for inbox: $inboxId", e)
            throw e
        }
    }

    fun loadIdentity(inboxId: String): StoredIdentity? {
        return try {
            // Try to load from new encrypted storage first
            val encryptedPrivateKey = sharedPreferences.getString("${PRIVATE_KEY_PREFIX}$inboxId", null)
            val encryptedDbKey = sharedPreferences.getString("${DB_KEY_PREFIX}$inboxId", null)

            if (encryptedPrivateKey != null && encryptedDbKey != null) {
                // Decrypt using AndroidKeyStore
                val privateKeyData = decrypt(encryptedPrivateKey)
                val databaseKey = decrypt(encryptedDbKey)

                Log.d(TAG, "Loaded identity from new storage for inbox: $inboxId")
                return StoredIdentity(inboxId, privateKeyData, databaseKey)
            }

            // Not found in new storage, try to migrate from legacy EncryptedSharedPreferences
            Log.d(TAG, "Identity not found in new storage, attempting migration for inbox: $inboxId")
            val migrated = migrateFromLegacy(inboxId)
            if (migrated != null) {
                Log.d(TAG, "Successfully migrated identity for inbox: $inboxId")
                return migrated
            }

            Log.d(TAG, "No identity found for inbox: $inboxId")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load identity for inbox: $inboxId", e)
            null
        }
    }

    /**
     * Attempts to migrate identity from legacy EncryptedSharedPreferences to new storage.
     * Returns the migrated identity if successful, null otherwise.
     */
    private fun migrateFromLegacy(inboxId: String): StoredIdentity? {
        val legacy = legacyPreferences ?: return null

        return try {
            val encodedPrivateKey = legacy.getString("${PRIVATE_KEY_PREFIX}$inboxId", null)
            val encodedDbKey = legacy.getString("${DB_KEY_PREFIX}$inboxId", null)

            if (encodedPrivateKey == null || encodedDbKey == null) {
                Log.d(TAG, "No legacy identity found for inbox: $inboxId")
                return null
            }

            // Decode from legacy storage (was base64 encoded, but encrypted by EncryptedSharedPreferences)
            val privateKeyData = Base64.decode(encodedPrivateKey, Base64.DEFAULT)
            val databaseKey = Base64.decode(encodedDbKey, Base64.DEFAULT)

            val identity = StoredIdentity(inboxId, privateKeyData, databaseKey)

            // Re-save using new encryption method
            val privateKey = org.xmtp.android.library.messages.PrivateKey.parseFrom(privateKeyData)
            val privateKeyBuilder = PrivateKeyBuilder(privateKey)
            saveIdentity(inboxId, privateKeyBuilder, databaseKey)

            // Clean up legacy data
            legacy.edit().apply {
                remove("${PRIVATE_KEY_PREFIX}$inboxId")
                remove("${DB_KEY_PREFIX}$inboxId")
                apply()
            }

            Log.d(TAG, "Migrated and cleaned up legacy identity for inbox: $inboxId")
            identity
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate legacy identity for inbox: $inboxId", e)
            null
        }
    }

    companion object {
        private const val PRIVATE_KEY_PREFIX = "private_key_"
        private const val DB_KEY_PREFIX = "db_key_"
    }
}
