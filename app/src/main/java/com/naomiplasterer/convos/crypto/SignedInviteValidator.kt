package com.naomiplasterer.convos.crypto

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.ByteString
import com.naomiplasterer.convos.proto.InviteProtos
import com.naomiplasterer.convos.util.InviteConversationToken
import android.util.Log
import java.util.Date

private const val TAG = "SignedInviteValidator"

sealed class SignedInviteError : Exception {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)

    class InvalidSignature : SignedInviteError("Invalid signature in signed invite")
    class ConversationNotFound(conversationId: String) : SignedInviteError("Conversation $conversationId not found")
    class InvalidConversationType : SignedInviteError("Join request targets a DM instead of a group")
    class MissingTextContent : SignedInviteError("Message has no text content, not a join request")
    class InvalidInviteFormat : SignedInviteError("Message text is not a valid signed invite format")
    class Expired : SignedInviteError("Invite has expired")
    class ExpiredConversation : SignedInviteError("Conversation has expired")
    class InvalidProtobuf(cause: Throwable) : SignedInviteError("Failed to parse invite protobuf", cause)
}

data class JoinRequestResult(
    val conversationId: String,
    val conversationName: String?
)

object SignedInviteValidator {

    fun parseFromURLSafeSlug(slug: String): InviteProtos.SignedInvite {
        return try {
            // Remove * separators that were added for iMessage compatibility
            val cleanSlug = slug.trim().removingSeparator("*")
            val bytes = Base64URL.decode(cleanSlug)

            // Check if data is compressed (starts with compression marker)
            val protobufData = if (bytes.isNotEmpty() && bytes[0] == InviteCompression.COMPRESSION_MARKER) {
                InviteCompression.decompress(bytes)
                    ?: throw SignedInviteError.InvalidInviteFormat()
            } else {
                bytes
            }

            InviteProtos.SignedInvite.parseFrom(protobufData)
        } catch (e: InvalidProtocolBufferException) {
            throw SignedInviteError.InvalidProtobuf(e)
        } catch (e: SignedInviteError) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse signed invite", e)
            throw SignedInviteError.InvalidInviteFormat()
        }
    }

    fun toURLSafeSlug(signedInvite: InviteProtos.SignedInvite): String {
        val protobufData = signedInvite.toByteArray()
        // Try to compress the data (only use if smaller)
        val dataToEncode = InviteCompression.compressIfSmaller(protobufData) ?: protobufData
        // Encode to base64 URL and insert * separators every 300 chars for iMessage compatibility
        return Base64URL.encode(dataToEncode).insertingSeparator("*", 300)
    }

    fun getPayload(signedInvite: InviteProtos.SignedInvite): InviteProtos.InvitePayload {
        return try {
            InviteProtos.InvitePayload.parseFrom(signedInvite.payload)
        } catch (e: Exception) {
            throw SignedInviteError.InvalidProtobuf(e)
        }
    }

    fun hasExpired(signedInvite: InviteProtos.SignedInvite): Boolean {
        val payload = getPayload(signedInvite)
        if (!payload.hasExpiresAtUnix()) {
            return false
        }
        val expiresAtMillis = payload.expiresAtUnix * 1000
        return System.currentTimeMillis() > expiresAtMillis
    }

    fun conversationHasExpired(signedInvite: InviteProtos.SignedInvite): Boolean {
        val payload = getPayload(signedInvite)
        if (!payload.hasConversationExpiresAtUnix()) {
            return false
        }
        val conversationExpiresAtMillis = payload.conversationExpiresAtUnix * 1000
        return System.currentTimeMillis() > conversationExpiresAtMillis
    }

    fun verify(signedInvite: InviteProtos.SignedInvite, client: org.xmtp.android.library.Client): Boolean {
        return try {
            // Use the exact payload bytes that were signed (stored in the SignedInvite)
            // Do NOT parse and re-serialize as that may change the bytes
            val payloadBytes = signedInvite.payload.toByteArray()
            val signature = signedInvite.signature.toByteArray()

            Log.d(TAG, "Verifying signature - payload size: ${payloadBytes.size}, signature size: ${signature.size}")

            // Check if this is a recoverable signature (65 bytes) from iOS
            if (signature.size == 65) {
                // iOS-style recoverable signature
                // Extract the recovery ID (last byte)
                val recoveryId = signature[64].toInt()
                val signatureData = signature.sliceArray(0 until 64)

                // iOS signs the SHA256 hash
                val messageHash = java.security.MessageDigest.getInstance("SHA-256").digest(payloadBytes)

                Log.d(TAG, "iOS signature detected (65 bytes), recovery ID: $recoveryId")

                // Try to recover the public key from the signature
                // Note: The XMTP SDK might not directly support this, so we may need to use lower-level crypto
                // For now, we'll try to verify using the hash
                val hashHex = messageHash.joinToString("") { "%02x".format(it) }

                // Try verification with the full signature including recovery ID
                val isValid = client.verifySignature(hashHex, signature) ||
                              // Also try with just the 64-byte signature part
                              client.verifySignature(hashHex, signatureData)

                Log.d(TAG, "iOS signature verification result: $isValid")
                isValid
            } else {
                // Android-style signature (standard ECDSA)
                val messageHash = java.security.MessageDigest.getInstance("SHA-256").digest(payloadBytes)
                val hashHex = messageHash.joinToString("") { "%02x".format(it) }

                Log.d(TAG, "Android signature detected (${signature.size} bytes)")

                val isValid = client.verifySignature(hashHex, signature)
                Log.d(TAG, "Android signature verification result: $isValid")
                isValid
            }
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification failed", e)
            false
        }
    }

    fun sign(payload: InviteProtos.InvitePayload, client: org.xmtp.android.library.Client): ByteArray {
        val payloadBytes = payload.toByteArray()

        // iOS signs the SHA256 hash of the bytes, not the raw bytes
        // Since Android SDK only accepts String, we need to hash and convert to hex
        val messageHash = java.security.MessageDigest.getInstance("SHA-256").digest(payloadBytes)
        val hashHex = messageHash.joinToString("") { "%02x".format(it) }

        Log.d(TAG, "Signing payload - size: ${payloadBytes.size}, hash: ${hashHex.take(16)}..., tag: ${payload.tag}")

        // The XMTP Android SDK's signWithInstallationKey should create a recoverable signature
        // but we need to verify the format
        val signature = client.signWithInstallationKey(hashHex)
        Log.d(TAG, "Generated signature size: ${signature.size} bytes")

        // If the signature is not 65 bytes (recoverable format), we may need to adjust
        // A recoverable signature should be 65 bytes (64 bytes + 1 recovery ID)
        if (signature.size != 65) {
            Log.w(TAG, "Signature is ${signature.size} bytes, expected 65 for recoverable signature")
            // The XMTP SDK should already be creating recoverable signatures
            // If not, we'd need to use lower-level crypto libraries
        }

        return signature
    }

    fun recoverPublicKey(signedInvite: InviteProtos.SignedInvite): ByteArray {
        val payloadBytes = signedInvite.payload.toByteArray()
        val signature = signedInvite.signature.toByteArray()

        return uniffi.xmtpv3.ethereumSignRecoverable(
            payloadBytes,
            signature,
            false
        )
    }

    fun createSignedInvite(
        conversationId: String,
        creatorInboxId: String,
        client: org.xmtp.android.library.Client,
        inviteTag: String,
        name: String? = null,
        description: String? = null,
        imageURL: String? = null,
        expiresAt: Date? = null,
        conversationExpiresAt: Date? = null,
        expiresAfterUse: Boolean = false
    ): InviteProtos.SignedInvite {
        val privateKey = client.installationId.toByteArray()
        val conversationTokenResult = InviteConversationToken.makeConversationToken(
            conversationId,
            creatorInboxId,
            privateKey
        )

        val conversationToken = conversationTokenResult.getOrNull()
        if (conversationToken == null) {
            throw SignedInviteError.InvalidInviteFormat()
        }

        // Convert string creatorInboxId to bytes (hex-decoded for compactness)
        val creatorInboxIdBytes = try {
            // If inbox ID is hex string, decode it
            if (creatorInboxId.matches(Regex("^[0-9a-fA-F]+$"))) {
                creatorInboxId.chunked(2).map { hexByte -> hexByte.toInt(16).toByte() }.toByteArray()
            } else {
                // Otherwise use UTF-8 bytes
                creatorInboxId.toByteArray(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            // Fallback to UTF-8 bytes
            creatorInboxId.toByteArray(Charsets.UTF_8)
        }

        val payloadBuilder = InviteProtos.InvitePayload.newBuilder()
            .setConversationToken(ByteString.copyFrom(Base64URL.decode(conversationToken)))
            .setCreatorInboxId(ByteString.copyFrom(creatorInboxIdBytes))
            .setTag(inviteTag)
            .setExpiresAfterUse(expiresAfterUse)

        name?.let { payloadBuilder.name = it }
        description?.let { payloadBuilder.description = it }
        imageURL?.let { payloadBuilder.imageURL = it }
        expiresAt?.let {
            val seconds = it.time / 1000
            payloadBuilder.expiresAtUnix = seconds
        }
        conversationExpiresAt?.let {
            val seconds = it.time / 1000
            payloadBuilder.conversationExpiresAtUnix = seconds
        }

        val payload = payloadBuilder.build()
        val signature = sign(payload, client)

        return InviteProtos.SignedInvite.newBuilder()
            .setPayload(ByteString.copyFrom(payload.toByteArray()))
            .setSignature(ByteString.copyFrom(signature))
            .build()
    }
}

/**
 * Extension function to convert creator inbox ID from protobuf bytes to hex string.
 * iOS stores inbox IDs as hex-decoded bytes for compactness, so we need to hex-encode
 * them back to strings when reading.
 */
fun InviteProtos.InvitePayload.getCreatorInboxIdString(): String {
    return this.creatorInboxId.toByteArray()
        .joinToString("") { "%02x".format(it) }
}
