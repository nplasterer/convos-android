package com.naomiplasterer.convos.crypto

import com.google.crypto.tink.subtle.Hkdf
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

private const val TAG = "InviteConversationToken"

object InviteConversationToken {
    const val FORMAT_VERSION: Byte = 1
    private const val SALT = "ConvosInviteV1"
    private const val NONCE_LENGTH = 12
    private const val AUTH_TAG_LENGTH = 16
    private const val MIN_ENCODED_SIZE = 1 + NONCE_LENGTH + 3 + AUTH_TAG_LENGTH
    const val UUID_CODE_SIZE = 1 + NONCE_LENGTH + 16 + 1 + AUTH_TAG_LENGTH
    const val MAX_STRING_LENGTH = 65535

    private const val TYPE_TAG_UUID = 0x01.toByte()
    private const val TYPE_TAG_UTF8 = 0x02.toByte()

    sealed class Error(message: String) : Exception(message) {
        class Truncated : Error("Invite code data is truncated")
        class MissingVersion : Error("Invite code is missing version byte")
        class UnsupportedVersion(version: Int) : Error("Unsupported invite code version: $version, expected $FORMAT_VERSION")
        class BadKeyMaterial : Error("Invalid private key material")
        class CryptoOpenFailed : Error("Failed to decrypt invite code")
        class InvalidFormat(details: String) : Error("Invalid invite code format: $details")
        class StringTooLong(length: Int) : Error("Conversation ID too long: $length bytes, max $MAX_STRING_LENGTH")
        class EmptyConversationId : Error("Conversation ID cannot be empty")
    }

    fun makeConversationToken(
        conversationId: String,
        creatorInboxId: String,
        secp256k1PrivateKey: ByteArray
    ): String {
        val key = deriveKey(secp256k1PrivateKey, creatorInboxId)
        val plaintext = packConversationId(conversationId)
        val aad = creatorInboxId.toByteArray(Charsets.UTF_8)

        val sealed = chachaSeal(plaintext, key, aad)

        val output = ByteArray(1 + sealed.size)
        output[0] = FORMAT_VERSION
        System.arraycopy(sealed, 0, output, 1, sealed.size)

        return Base64URL.encode(output)
    }

    fun decodeConversationToken(
        conversationToken: String,
        creatorInboxId: String,
        secp256k1PrivateKey: ByteArray
    ): String {
        val data = Base64URL.decode(conversationToken)

        if (data.size < MIN_ENCODED_SIZE) {
            throw Error.InvalidFormat("Code too short: ${data.size} bytes, minimum $MIN_ENCODED_SIZE")
        }

        if (data.isEmpty()) {
            throw Error.MissingVersion()
        }

        val version = data[0]
        if (version != FORMAT_VERSION) {
            throw Error.UnsupportedVersion(version.toInt())
        }

        val combined = data.copyOfRange(1, data.size)
        val key = deriveKey(secp256k1PrivateKey, creatorInboxId)
        val aad = creatorInboxId.toByteArray(Charsets.UTF_8)

        val plaintext = chachaOpen(combined, key, aad)
        return unpackConversationId(plaintext)
    }

    private fun deriveKey(privateKey: ByteArray, inboxId: String): ByteArray {
        if (privateKey.isEmpty()) {
            throw Error.BadKeyMaterial()
        }

        val info = "inbox:$inboxId".toByteArray(Charsets.UTF_8)
        val salt = SALT.toByteArray(Charsets.UTF_8)

        return Hkdf.computeHkdf(
            "HmacSha256",
            privateKey,
            salt,
            info,
            32
        )
    }

    private fun chachaSeal(plaintext: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH)
        java.security.SecureRandom().nextBytes(nonce)

        val ciphertext = try {
            javax.crypto.Cipher.getInstance("ChaCha20-Poly1305").apply {
                val keySpec = javax.crypto.spec.SecretKeySpec(key, "ChaCha20")
                val ivSpec = javax.crypto.spec.IvParameterSpec(nonce)
                init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, ivSpec)
                updateAAD(aad)
            }.doFinal(plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "ChaCha20Poly1305 encryption failed", e)
            throw Error.InvalidFormat("Encryption failed: ${e.message}")
        }

        val output = ByteArray(NONCE_LENGTH + ciphertext.size)
        System.arraycopy(nonce, 0, output, 0, NONCE_LENGTH)
        System.arraycopy(ciphertext, 0, output, NONCE_LENGTH, ciphertext.size)

        return output
    }

    private fun chachaOpen(combined: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        if (combined.size < NONCE_LENGTH + AUTH_TAG_LENGTH) {
            throw Error.InvalidFormat("Malformed encrypted data")
        }

        val nonce = combined.copyOfRange(0, NONCE_LENGTH)
        val ciphertext = combined.copyOfRange(NONCE_LENGTH, combined.size)

        return try {
            javax.crypto.Cipher.getInstance("ChaCha20-Poly1305").apply {
                val keySpec = javax.crypto.spec.SecretKeySpec(key, "ChaCha20")
                val ivSpec = javax.crypto.spec.IvParameterSpec(nonce)
                init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec)
                updateAAD(aad)
            }.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "ChaCha20Poly1305 decryption failed", e)
            throw Error.CryptoOpenFailed()
        }
    }

    private fun packConversationId(id: String): ByteArray {
        if (id.isEmpty()) {
            throw Error.EmptyConversationId()
        }

        val uuidValue = tryParseUUID(id)
        if (uuidValue != null) {
            val output = ByteArray(1 + 16)
            output[0] = TYPE_TAG_UUID

            val buffer = ByteBuffer.wrap(output, 1, 16)
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.putLong(uuidValue.mostSignificantBits)
            buffer.putLong(uuidValue.leastSignificantBits)

            return output
        } else {
            val bytes = id.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_STRING_LENGTH) {
                throw Error.StringTooLong(bytes.size)
            }

            val lengthBytes = when {
                bytes.isNotEmpty() && bytes.size <= 255 -> byteArrayOf(bytes.size.toByte())
                else -> byteArrayOf(
                    0,
                    ((bytes.size shr 8) and 0xFF).toByte(),
                    (bytes.size and 0xFF).toByte()
                )
            }

            val output = ByteArray(1 + lengthBytes.size + bytes.size)
            output[0] = TYPE_TAG_UTF8
            System.arraycopy(lengthBytes, 0, output, 1, lengthBytes.size)
            System.arraycopy(bytes, 0, output, 1 + lengthBytes.size, bytes.size)

            return output
        }
    }

    private fun unpackConversationId(data: ByteArray): String {
        if (data.isEmpty()) {
            throw Error.InvalidFormat("Missing or invalid type tag")
        }

        val tag = data[0]
        var offset = 1

        return when (tag) {
            TYPE_TAG_UUID -> {
                if (data.size < offset + 16) {
                    throw Error.InvalidFormat("UUID payload too short: need 16 bytes, have ${data.size - offset}")
                }

                val buffer = ByteBuffer.wrap(data, offset, 16)
                buffer.order(ByteOrder.BIG_ENDIAN)
                val mostSigBits = buffer.long
                val leastSigBits = buffer.long

                val uuid = UUID(mostSigBits, leastSigBits)
                uuid.toString().lowercase()
            }

            TYPE_TAG_UTF8 -> {
                if (data.size <= offset) {
                    throw Error.InvalidFormat("String payload missing length byte")
                }

                val len1 = data[offset].toInt() and 0xFF
                offset += 1

                val length = if (len1 > 0) {
                    len1
                } else {
                    if (data.size < offset + 2) {
                        throw Error.InvalidFormat("String payload missing 2-byte length")
                    }
                    val high = data[offset].toInt() and 0xFF
                    val low = data[offset + 1].toInt() and 0xFF
                    offset += 2
                    (high shl 8) or low
                }

                if (length == 0) {
                    throw Error.EmptyConversationId()
                }

                if (data.size < offset + length) {
                    throw Error.InvalidFormat("String payload truncated: need $length bytes, have ${data.size - offset}")
                }

                val bytes = data.copyOfRange(offset, offset + length)
                String(bytes, Charsets.UTF_8)
            }

            else -> throw Error.InvalidFormat("Missing or invalid type tag")
        }
    }

    private fun tryParseUUID(s: String): UUID? {
        return try {
            UUID.fromString(s)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
