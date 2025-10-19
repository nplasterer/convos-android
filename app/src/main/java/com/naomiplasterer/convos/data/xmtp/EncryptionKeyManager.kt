package com.naomiplasterer.convos.data.xmtp

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptionKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "xmtp_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getOrCreateKey(address: String): ByteArray {
        val keyName = "key_$address"
        val existingKey = sharedPreferences.getString(keyName, null)

        return if (existingKey != null) {
            Timber.d("Retrieved existing encryption key for address: $address")
            Base64.decode(existingKey, Base64.DEFAULT)
        } else {
            Timber.d("Generating new encryption key for address: $address")
            val newKey = generateEncryptionKey()
            val encodedKey = Base64.encodeToString(newKey, Base64.DEFAULT)
            sharedPreferences.edit().putString(keyName, encodedKey).apply()
            newKey
        }
    }

    private fun generateEncryptionKey(): ByteArray {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        return key
    }

    fun clearKey(address: String) {
        val keyName = "key_$address"
        sharedPreferences.edit().remove(keyName).apply()
        Timber.d("Cleared encryption key for address: $address")
    }

    fun clearAllKeys() {
        sharedPreferences.edit().clear().apply()
        Timber.d("Cleared all encryption keys")
    }
}
