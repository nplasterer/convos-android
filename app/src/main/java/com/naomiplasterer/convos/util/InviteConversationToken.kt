package com.naomiplasterer.convos.util

import android.util.Base64
import com.google.crypto.tink.subtle.ChaCha20Poly1305
import com.google.crypto.tink.subtle.Hkdf
import android.util.Log
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.UUID

private const val TAG = "InviteConversationToken"

/**
 * Compact, public invite conversation tokens that only the creator can decode.
 *
 * Cryptographic Design:
 * - Key Derivation: HKDF-SHA256(privateKey, salt="ConvosInviteV1", info="inbox:<inboxId>")
 * - AEAD: ChaCha20-Poly1305 with AAD = creatorInboxId (binds token to creator's identity)
 *
 * Binary Format Specification:
 * | version (1 byte) | nonce (12 bytes) | ciphertext (variable) | auth_tag (16 bytes) |
 *
 * Payload Types:
 * - type_tag = 0x01: UUID format (16 bytes)
 * - type_tag = 0x02: UTF-8 String format (variable length with 1-2 byte length prefix)
 */
object InviteConversationToken {
    private const val FORMAT_VERSION: Byte = 1
    private const val SALT = "ConvosInviteV1"
    private const val NONCE_LENGTH = 12
    private const val AUTH_TAG_LENGTH = 16
    private const val MIN_ENCODED_SIZE = 1 + NONCE_LENGTH + 3 + AUTH_TAG_LENGTH // 32 bytes

    private const val TAG_UUID: Byte = 0x01
    private const val TAG_UTF8: Byte = 0x02

    /**
     * Make a public invite conversation token that the creator can later decrypt to the conversationId.
     *
     * @param conversationId The conversation id (hex string from XMTP)
     * @param creatorInboxId The creator's inbox id
     * @param secp256k1PrivateKey 32-byte raw secp256k1 private key data
     * @return Base64URL (no padding) opaque conversation token suitable for URLs
     */
    fun makeConversationToken(
        conversationId: String,
        creatorInboxId: String,
        secp256k1PrivateKey: ByteArray
    ): Result<String> {
        return try {
            require(conversationId.isNotEmpty()) { "Conversation ID cannot be empty" }
            require(secp256k1PrivateKey.isNotEmpty()) { "Private key cannot be empty" }

            val key = deriveKey(secp256k1PrivateKey, creatorInboxId)
            val plaintext = packConversationId(conversationId)
            val aad = creatorInboxId.toByteArray(Charsets.UTF_8)

            val cipher = ChaCha20Poly1305(key)

            // ChaCha20Poly1305.encrypt returns nonce (12 bytes) + ciphertext + tag (16 bytes)
            // Tink automatically generates and prepends the nonce
            val encrypted = cipher.encrypt(plaintext, aad)

            // Prepend version
            val output = ByteBuffer.allocate(1 + encrypted.size)
            output.put(FORMAT_VERSION)
            output.put(encrypted)

            val base64url = Base64.encodeToString(
                output.array(),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            Result.success(base64url)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create conversation token", e)
            Result.failure(e)
        }
    }

    /**
     * Recover the original conversationId from a public invite conversation token.
     *
     * @param conversationToken Base64URL opaque string produced by makeConversationToken
     * @param creatorInboxId Same inbox id used when generating the token
     * @param secp256k1PrivateKey Same 32-byte private key used when generating
     * @return The original conversationId on success
     */
    fun decodeConversationToken(
        conversationToken: String,
        creatorInboxId: String,
        secp256k1PrivateKey: ByteArray
    ): Result<String> {
        return try {
            val data = Base64.decode(
                conversationToken,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            require(data.size >= MIN_ENCODED_SIZE) {
                "Token too short: ${data.size} bytes, minimum $MIN_ENCODED_SIZE"
            }

            val version = data[0]
            require(version == FORMAT_VERSION) {
                "Unsupported version: $version, expected $FORMAT_VERSION"
            }

            // Extract encrypted data (nonce + ciphertext + tag)
            val encrypted = data.copyOfRange(1, data.size)

            val key = deriveKey(secp256k1PrivateKey, creatorInboxId)
            val aad = creatorInboxId.toByteArray(Charsets.UTF_8)

            val cipher = ChaCha20Poly1305(key)
            val plaintext = cipher.decrypt(encrypted, aad)

            val conversationId = unpackConversationId(plaintext)
            Result.success(conversationId)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Failed to decrypt conversation token", e)
            Result.failure(IllegalArgumentException("Invalid or corrupted invite token", e))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode conversation token", e)
            Result.failure(e)
        }
    }

    private fun deriveKey(privateKey: ByteArray, inboxId: String): ByteArray {
        require(privateKey.isNotEmpty()) { "Private key cannot be empty" }

        val info = "inbox:$inboxId".toByteArray(Charsets.UTF_8)
        val salt = SALT.toByteArray(Charsets.UTF_8)

        return Hkdf.computeHkdf(
            "HMACSHA256",
            privateKey,
            salt,
            info,
            32 // 256 bits for ChaCha20-Poly1305
        )
    }

    private fun packConversationId(id: String): ByteArray {
        // Try to parse as UUID first (more compact encoding)
        val uuid = try {
            UUID.fromString(id)
        } catch (e: IllegalArgumentException) {
            null
        }

        return if (uuid != null) {
            // UUID format: [tag:1][uuid_bytes:16]
            val buffer = ByteBuffer.allocate(17)
            buffer.put(TAG_UUID)
            buffer.putLong(uuid.mostSignificantBits)
            buffer.putLong(uuid.leastSignificantBits)
            buffer.array()
        } else {
            // UTF-8 string format: [tag:1][length:1-3][utf8_data:variable]
            val bytes = id.toByteArray(Charsets.UTF_8)
            require(bytes.size <= 65535) { "Conversation ID too long: ${bytes.size} bytes" }

            val buffer = if (bytes.size <= 255) {
                // Short format: [tag][length_byte][data]
                ByteBuffer.allocate(2 + bytes.size)
                    .put(TAG_UTF8)
                    .put(bytes.size.toByte())
            } else {
                // Long format: [tag][0x00][length_high][length_low][data]
                ByteBuffer.allocate(4 + bytes.size)
                    .put(TAG_UTF8)
                    .put(0x00.toByte())
                    .put((bytes.size shr 8 and 0xff).toByte())
                    .put((bytes.size and 0xff).toByte())
            }
            buffer.put(bytes)
            buffer.array()
        }
    }

    private fun unpackConversationId(data: ByteArray): String {
        require(data.isNotEmpty()) { "Data is empty" }

        val tag = data[0]
        return when (tag) {
            TAG_UUID -> {
                require(data.size >= 17) { "UUID payload too short" }
                val buffer = ByteBuffer.wrap(data, 1, 16)
                val mostSigBits = buffer.long
                val leastSigBits = buffer.long
                UUID(mostSigBits, leastSigBits).toString().lowercase()
            }
            TAG_UTF8 -> {
                require(data.size > 1) { "String payload missing length" }
                val len1 = data[1].toInt() and 0xff

                val (length, offset) = if (len1 > 0) {
                    len1 to 2
                } else {
                    require(data.size >= 4) { "String payload missing 2-byte length" }
                    val high = data[2].toInt() and 0xff
                    val low = data[3].toInt() and 0xff
                    val len = (high shl 8) or low
                    len to 4
                }

                require(length > 0) { "Conversation ID cannot be empty" }
                require(data.size >= offset + length) { "String payload truncated" }

                String(data, offset, length, Charsets.UTF_8)
            }
            else -> throw IllegalArgumentException("Invalid type tag: $tag")
        }
    }
}
