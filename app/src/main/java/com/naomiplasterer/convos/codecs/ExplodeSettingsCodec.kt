package com.naomiplasterer.convos.codecs

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.protobuf.kotlin.toByteString
import org.xmtp.android.library.codecs.ContentCodec
import org.xmtp.android.library.codecs.ContentTypeIdBuilder
import org.xmtp.android.library.codecs.EncodedContent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ExplodeSettings represents the settings for an exploding conversation.
 * This content type is sent to signal that a conversation should be deleted.
 *
 * IMPORTANT: Field names must match iOS exactly (camelCase) for cross-platform compatibility.
 */
data class ExplodeSettings(
    val expiresAt: Date
)

/**
 * Content type identifier for ExplodeSettings messages.
 * This matches the iOS implementation for cross-platform compatibility.
 */
val ContentTypeExplodeSettings = ContentTypeIdBuilder.builderFromAuthorityId(
    "convos.org",
    "explode_settings",
    versionMajor = 1,
    versionMinor = 0
)

/**
 * Codec for encoding/decoding ExplodeSettings messages.
 * This allows conversations to send "explode" signals that trigger
 * conversation deletion across all participants.
 */
class ExplodeSettingsCodec : ContentCodec<ExplodeSettings> {
    override val contentType = ContentTypeExplodeSettings

    // ISO8601 date format to match iOS implementation
    private val iso8601Format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .create()

    override fun encode(content: ExplodeSettings): EncodedContent {
        return EncodedContent.newBuilder().also {
            it.type = contentType
            it.content = gson.toJson(content).toByteArray(Charsets.UTF_8).toByteString()
        }.build()
    }

    override fun decode(content: EncodedContent): ExplodeSettings {
        val jsonString = content.content.toStringUtf8()

        if (jsonString.isEmpty()) {
            throw IllegalArgumentException("ExplodeSettings content is empty")
        }

        return try {
            gson.fromJson(jsonString, ExplodeSettings::class.java)
                ?: throw IllegalArgumentException("Failed to parse ExplodeSettings")
        } catch (e: JsonSyntaxException) {
            throw IllegalArgumentException("Invalid JSON format for ExplodeSettings", e)
        }
    }

    override fun fallback(content: ExplodeSettings): String {
        return "Conversation expires at ${content.expiresAt}"
    }

    override fun shouldPush(content: ExplodeSettings): Boolean {
        // Push to ensure all devices (including offline ones) receive the message
        // But the notification will be silently dropped (not shown to user)
        return true
    }
}