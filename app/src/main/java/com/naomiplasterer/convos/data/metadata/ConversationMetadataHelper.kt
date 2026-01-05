package com.naomiplasterer.convos.data.metadata

import android.util.Log
import com.google.protobuf.ByteString
import com.naomiplasterer.convos.crypto.Base64URL
import com.naomiplasterer.convos.crypto.InviteCompression
import com.naomiplasterer.convos.proto.ConversationMetadataProtos
import org.xmtp.android.library.Conversation
import java.util.UUID

private const val TAG = "ConversationMetadataHelper"

/**
 * Helper class for handling conversation metadata storage in XMTP groups.
 * Matches iOS implementation using appData() instead of description().
 */
object ConversationMetadataHelper {

    /**
     * Generate metadata for a new conversation
     */
    fun generateMetadata(
        tag: String = UUID.randomUUID().toString(),
        expiresAtUnix: Long? = null,
        profiles: List<ConversationMetadataProtos.ConversationProfile>? = null
    ): ConversationMetadataProtos.ConversationCustomMetadata {
        val builder = ConversationMetadataProtos.ConversationCustomMetadata.newBuilder()
            .setTag(tag)

        expiresAtUnix?.let { builder.expiresAtUnix = it }
        profiles?.let { builder.addAllProfiles(it) }

        return builder.build()
    }

    /**
     * Encode metadata to compact string format (matching iOS toCompactString())
     * Uses base64url encoding with optional compression
     */
    fun encodeMetadata(metadata: ConversationMetadataProtos.ConversationCustomMetadata): String {
        val protobufBytes = metadata.toByteArray()

        // Try compression, use if smaller
        val dataToEncode = InviteCompression.compressIfSmaller(protobufBytes) ?: protobufBytes

        // Base64url encode
        return Base64URL.encode(dataToEncode)
    }

    /**
     * Decode metadata from compact string format (matching iOS parseAppData())
     */
    fun decodeMetadata(encoded: String): ConversationMetadataProtos.ConversationCustomMetadata? {
        return try {
            Log.d(TAG, "📦 Decoding metadata from base64url string...")
            Log.d(TAG, "   Input length: ${encoded.length}")
            Log.d(TAG, "   Input (first 50): ${encoded.take(50)}")

            val bytes = Base64URL.decode(encoded)
            Log.d(TAG, "   Decoded byte array length: ${bytes.size}")

            // Check if compressed (first byte is compression marker)
            val protobufBytes =
                if (bytes.isNotEmpty() && bytes[0] == InviteCompression.COMPRESSION_MARKER) {
                    Log.d(TAG, "   Data is compressed, decompressing...")
                    InviteCompression.decompress(bytes) ?: bytes
                } else {
                    Log.d(TAG, "   Data is not compressed")
                    bytes
                }

            Log.d(TAG, "   Parsing protobuf from ${protobufBytes.size} bytes...")
            val metadata =
                ConversationMetadataProtos.ConversationCustomMetadata.parseFrom(protobufBytes)

            Log.d(TAG, "✅ Successfully decoded metadata!")
            Log.d(TAG, "   Tag: ${metadata.tag}")
            Log.d(TAG, "   Has expiresAtUnix: ${metadata.hasExpiresAtUnix()}")
            Log.d(TAG, "   Profiles count: ${metadata.profilesCount}")

            metadata
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to decode metadata from: ${encoded.take(50)}...", e)
            Log.e(TAG, "   Error: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Store metadata in a group's description (temporary - until appData API is available)
     * TODO: Switch to appData() when XMTP Android SDK supports it
     */
    suspend fun storeMetadata(
        group: Conversation.Group,
        metadata: ConversationMetadataProtos.ConversationCustomMetadata
    ): Result<Unit> {
        return try {
            val encoded = encodeMetadata(metadata)
            // Store in description as base64url encoded string
            // TODO: Use group.updateAppData(encoded) when available
            group.group.updateDescription(encoded)
            Log.d(TAG, "Stored metadata in group description (temporary): tag=${metadata.tag}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store metadata in group", e)
            Result.failure(e)
        }
    }

    /**
     * Retrieve metadata from a group's description (temporary - until appData API is available)
     * TODO: Switch to appData() when XMTP Android SDK supports it
     */
    suspend fun retrieveMetadata(group: Conversation.Group): ConversationMetadataProtos.ConversationCustomMetadata? {
        return try {
            // Try to get from description (base64url encoded)
            // TODO: Use group.appData() when available
            val description = group.group.description()
            if (description.isEmpty()) {
                Log.d(TAG, "No metadata found in group ${group.id}")
                return null
            }

            // Check format: hex (legacy) first, then base64url
            val metadata = if (description.matches(Regex("^[0-9a-fA-F]+$"))) {
                // Legacy hex format - parse as hex-encoded protobuf
                parseLegacyHexMetadata(description)
            } else if (description.matches(Regex("^[A-Za-z0-9_-]+$"))) {
                // Base64url format - decode as base64url
                decodeMetadata(description)
            } else {
                // Unknown format
                Log.w(TAG, "Unknown metadata format in group description")
                null
            }

            Log.d(TAG, "Retrieved metadata from group ${group.id}: tag=${metadata?.tag}")
            metadata
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve metadata from group", e)
            null
        }
    }

    /**
     * Extract member profiles from metadata
     */
    fun extractProfiles(
        metadata: ConversationMetadataProtos.ConversationCustomMetadata
    ): Map<String, ConversationMetadataProtos.ConversationProfile> {
        val profiles = mutableMapOf<String, ConversationMetadataProtos.ConversationProfile>()

        for (profile in metadata.profilesList) {
            // Convert ByteString inbox ID to hex string
            val inboxIdHex = profile.inboxId.toByteArray()
                .joinToString("") { "%02x".format(it) }
            profiles[inboxIdHex] = profile
        }

        return profiles
    }

    /**
     * Create a profile for a member
     */
    fun createProfile(
        inboxId: String,
        name: String? = null,
        image: String? = null
    ): ConversationMetadataProtos.ConversationProfile {
        // Convert hex string inbox ID to ByteString
        val inboxIdBytes = if (inboxId.matches(Regex("^[0-9a-fA-F]+$"))) {
            inboxId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            inboxId.toByteArray(Charsets.UTF_8)
        }

        val builder = ConversationMetadataProtos.ConversationProfile.newBuilder()
            .setInboxId(ByteString.copyFrom(inboxIdBytes))

        name?.let { builder.name = it }
        image?.let { builder.image = it }

        return builder.build()
    }

    /**
     * Parse metadata from legacy hex-encoded description field (for backwards compatibility)
     */
    fun parseLegacyHexMetadata(hexString: String): ConversationMetadataProtos.ConversationCustomMetadata? {
        return try {
            val bytes = hexString.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            ConversationMetadataProtos.ConversationCustomMetadata.parseFrom(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse legacy hex metadata", e)
            null
        }
    }
}