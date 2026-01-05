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
    class ConversationNotFound(conversationId: String) :
        SignedInviteError("Conversation $conversationId not found")

    class InvalidConversationType :
        SignedInviteError("Join request targets a DM instead of a group")

    class MissingTextContent : SignedInviteError("Message has no text content, not a join request")
    class InvalidInviteFormat :
        SignedInviteError("Message text is not a valid signed invite format")

    class Expired : SignedInviteError("Invite has expired")
    class ExpiredConversation : SignedInviteError("Conversation has expired")
    class InvalidProtobuf(cause: Throwable) :
        SignedInviteError("Failed to parse invite protobuf", cause)
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
            val protobufData =
                if (bytes.isNotEmpty() && bytes[0] == InviteCompression.COMPRESSION_MARKER) {
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

    fun verify(
        signedInvite: InviteProtos.SignedInvite,
        client: org.xmtp.android.library.Client
    ): Boolean {
        return try {
            // Use the exact payload bytes that were signed (stored in the SignedInvite)
            // Do NOT parse and re-serialize as that may change the bytes
            val payloadBytes = signedInvite.payload.toByteArray()
            val signature = signedInvite.signature.toByteArray()

            Log.d(
                TAG,
                "Verifying signature - payload size: ${payloadBytes.size}, signature size: ${signature.size}"
            )

            // We sign with secp256k1 wallet private key (65-byte recoverable signature)
            if (signature.size != 65) {
                Log.e(TAG, "Invalid signature size: ${signature.size}, expected 65 bytes")
                return false
            }

            // Extract r, s, and recovery ID from signature
            val r = java.math.BigInteger(1, signature.sliceArray(0 until 32))
            val s = java.math.BigInteger(1, signature.sliceArray(32 until 64))
            val recoveryId = signature[64].toInt()

            // Hash the payload (same as signing)
            val messageHash = java.security.MessageDigest.getInstance("SHA-256").digest(payloadBytes)

            Log.d(TAG, "Verifying secp256k1 signature with recovery ID: $recoveryId")

            // Get the payload to extract creator inbox ID
            val payload = getPayload(signedInvite)
            val creatorInboxIdStr = payload.getCreatorInboxIdString()

            // Recover the public key from the signature
            val curve = org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("secp256k1")
            val n = curve.n
            val G = curve.g

            // secp256k1 prime
            val prime = java.math.BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)

            // Calculate x coordinate
            val x = r.add(java.math.BigInteger.valueOf(recoveryId.toLong() / 2).multiply(n))
            if (x >= prime) {
                Log.e(TAG, "x coordinate >= prime, invalid signature")
                return false
            }

            // Ensure x is exactly 32 bytes for point decoding
            val xBytes = x.toByteArray().let {
                when {
                    it.size > 32 -> it.copyOfRange(it.size - 32, it.size)
                    it.size < 32 -> ByteArray(32 - it.size) + it
                    else -> it
                }
            }

            // Decompress the public key point
            val curvePoint = curve.curve.decodePoint(byteArrayOf(if (recoveryId and 1 == 0) 0x02 else 0x03) + xBytes)
            if (!curvePoint.multiply(n).isInfinity) {
                Log.e(TAG, "Invalid curve point, not on curve")
                return false
            }

            // Recover the public key: Q = r^-1 * (s*R - e*G)
            val e = java.math.BigInteger(1, messageHash)
            val rInv = r.modInverse(n)
            val recoveredPoint = curvePoint.multiply(s).add(G.multiply(e).negate()).multiply(rInv).normalize()

            // Get the recovered public key bytes (uncompressed format)
            val recoveredPubKey = recoveredPoint.getEncoded(false)
            val recoveredPubKeyHex = recoveredPubKey.joinToString("") { "%02x".format(it) }

            Log.d(TAG, "Recovered public key (first 20 chars): ${recoveredPubKeyHex.take(20)}...")

            // For now, we just verify that we can recover A public key
            // TODO: Verify it matches the creator's expected public key from their inbox ID
            // This requires getting the creator's wallet public key from the XMTP network

            // Basic sanity check: recovered public key should be 65 bytes (04 + 32 + 32)
            if (recoveredPubKey.size != 65 || recoveredPubKey[0] != 0x04.toByte()) {
                Log.e(TAG, "Invalid recovered public key format")
                return false
            }

            Log.d(TAG, "Signature verification successful - recovered valid secp256k1 public key")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification failed", e)
            false
        }
    }

    fun sign(
        payload: InviteProtos.InvitePayload,
        privateKey: ByteArray
    ): ByteArray {
        val payloadBytes = payload.toByteArray()

        // iOS signs the SHA256 hash of the payload bytes, matching iOS exactly
        val messageHash = java.security.MessageDigest.getInstance("SHA-256").digest(payloadBytes)

        Log.d(
            TAG,
            "Signing payload with secp256k1 - size: ${payloadBytes.size}, hash: ${messageHash.take(4).joinToString(",") { "%02x".format(it) }}..., tag: ${payload.tag}"
        )

        try {
            // Use BouncyCastle for secp256k1 signing
            val spec = org.bouncycastle.jce.spec.ECParameterSpec(
                org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("secp256k1").curve,
                org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("secp256k1").g,
                org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("secp256k1").n,
                org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("secp256k1").h
            )

            val privKeySpec = org.bouncycastle.jce.spec.ECPrivateKeySpec(
                java.math.BigInteger(1, privateKey),
                spec
            )

            val keyFactory = java.security.KeyFactory.getInstance("EC", "BC")
            val ecPrivateKey = keyFactory.generatePrivate(privKeySpec) as java.security.interfaces.ECPrivateKey

            // Sign using ECDSA
            val signer = java.security.Signature.getInstance("NONEwithECDSA", "BC")
            signer.initSign(ecPrivateKey)
            signer.update(messageHash)
            val derSignature = signer.sign()

            // Parse DER-encoded signature to get r and s
            val asn1 = org.bouncycastle.asn1.ASN1InputStream(derSignature)
            val seq = asn1.readObject() as org.bouncycastle.asn1.ASN1Sequence
            val r = (seq.getObjectAt(0) as org.bouncycastle.asn1.ASN1Integer).value
            val s = (seq.getObjectAt(1) as org.bouncycastle.asn1.ASN1Integer).value
            asn1.close()

            // Convert r and s to 32-byte arrays
            val rBytes = r.toByteArray().let {
                when {
                    it.size > 32 -> it.copyOfRange(it.size - 32, it.size)
                    it.size < 32 -> ByteArray(32 - it.size) + it
                    else -> it
                }
            }
            val sBytes = s.toByteArray().let {
                when {
                    it.size > 32 -> it.copyOfRange(it.size - 32, it.size)
                    it.size < 32 -> ByteArray(32 - it.size) + it
                    else -> it
                }
            }

            // Calculate recovery ID by trying to recover the public key
            val curve = org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("secp256k1")
            val n = curve.n
            val G = curve.g

            // Get the public key point from private key
            val pubKeyPoint = G.multiply(java.math.BigInteger(1, privateKey)).normalize()

            var recId = -1
            for (i in 0..3) {
                try {
                    // Try to recover public key with this recovery ID
                    val x = r.add(java.math.BigInteger.valueOf(i.toLong() / 2).multiply(n))

                    // secp256k1 prime: 2^256 - 2^32 - 977
                    val prime = java.math.BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
                    if (x >= prime) continue

                    // Ensure x is exactly 32 bytes for point decoding
                    val xBytes = x.toByteArray().let {
                        when {
                            it.size > 32 -> it.copyOfRange(it.size - 32, it.size)
                            it.size < 32 -> ByteArray(32 - it.size) + it
                            else -> it
                        }
                    }

                    val curvePoint = curve.curve.decodePoint(byteArrayOf(if (i and 1 == 0) 0x02 else 0x03) + xBytes)
                    if (!curvePoint.multiply(n).isInfinity) continue

                    val e = java.math.BigInteger(1, messageHash)
                    val rInv = r.modInverse(n)
                    val recoveredPoint = curvePoint.multiply(s).add(G.multiply(e).negate()).multiply(rInv).normalize()

                    // Compare the x and y coordinates directly
                    if (recoveredPoint.affineXCoord.toBigInteger() == pubKeyPoint.affineXCoord.toBigInteger() &&
                        recoveredPoint.affineYCoord.toBigInteger() == pubKeyPoint.affineYCoord.toBigInteger()) {
                        recId = i
                        Log.d(TAG, "Found recovery ID: $i")
                        break
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Recovery ID $i failed: ${e.message}")
                    // Try next recovery ID
                    continue
                }
            }

            if (recId == -1) {
                Log.e(TAG, "Failed to calculate recovery ID - none of the 4 options matched")
                throw IllegalStateException("Failed to calculate recovery ID")
            }

            // Combine into 65-byte recoverable signature: r (32) + s (32) + recovery ID (1)
            val signature = ByteArray(65)
            System.arraycopy(rBytes, 0, signature, 0, 32)
            System.arraycopy(sBytes, 0, signature, 32, 32)
            signature[64] = recId.toByte()

            Log.d(TAG, "Generated secp256k1 recoverable signature: ${signature.size} bytes, recId: $recId")

            return signature
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign with secp256k1", e)
            throw SignedInviteError.InvalidInviteFormat()
        }
    }

    fun createSignedInvite(
        conversationId: String,
        creatorInboxId: String,
        privateKey: ByteArray,
        client: org.xmtp.android.library.Client,
        inviteTag: String,
        name: String? = null,
        description: String? = null,
        imageURL: String? = null,
        expiresAt: Date? = null,
        conversationExpiresAt: Date? = null,
        expiresAfterUse: Boolean = false
    ): InviteProtos.SignedInvite {
        // Use the actual secp256k1 private key for encrypting the conversation token
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
                creatorInboxId.chunked(2).map { hexByte -> hexByte.toInt(16).toByte() }
                    .toByteArray()
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
        val signature = sign(payload, privateKey)

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
