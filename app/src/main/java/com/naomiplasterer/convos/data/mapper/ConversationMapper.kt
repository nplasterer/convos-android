package com.naomiplasterer.convos.data.mapper

import com.naomiplasterer.convos.data.local.entity.ConversationEntity
import com.naomiplasterer.convos.domain.model.Conversation
import com.naomiplasterer.convos.domain.model.ConsentState
import com.naomiplasterer.convos.domain.model.ConversationKind

fun ConversationEntity.toDomain(lastMessagePreview: String? = null): Conversation {
    return Conversation(
        id = id,
        inboxId = inboxId,
        clientId = clientId,
        topic = topic,
        creatorInboxId = creatorInboxId,
        inviteTag = inviteTag,
        consent = when (consent) {
            "allowed" -> ConsentState.ALLOWED
            "denied" -> ConsentState.DENIED
            else -> ConsentState.UNKNOWN
        },
        kind = when (kind) {
            "group" -> ConversationKind.GROUP
            "dm" -> ConversationKind.DM
            else -> ConversationKind.GROUP
        },
        name = name,
        description = description,
        imageUrl = imageUrl,
        isPinned = isPinned,
        isUnread = isUnread,
        isMuted = isMuted,
        isDraft = isDraft,
        createdAt = createdAt,
        lastMessageAt = lastMessageAt,
        expiresAt = expiresAt,
        lastMessagePreview = lastMessagePreview
    )
}

fun Conversation.toEntity(): ConversationEntity {
    return ConversationEntity(
        id = id,
        inboxId = inboxId,
        clientId = clientId,
        topic = topic,
        creatorInboxId = creatorInboxId,
        inviteTag = inviteTag,
        consent = when (consent) {
            ConsentState.ALLOWED -> "allowed"
            ConsentState.DENIED -> "denied"
            ConsentState.UNKNOWN -> "unknown"
        },
        kind = when (kind) {
            ConversationKind.GROUP -> "group"
            ConversationKind.DM -> "dm"
        },
        name = name,
        description = description,
        imageUrl = imageUrl,
        isPinned = isPinned,
        isUnread = isUnread,
        isMuted = isMuted,
        isDraft = isDraft,
        createdAt = createdAt,
        lastMessageAt = lastMessageAt,
        expiresAt = expiresAt
    )
}

suspend fun org.xmtp.android.library.Conversation.toEntity(inboxId: String): ConversationEntity {
    val isGroup = this is org.xmtp.android.library.Conversation.Group
    val group = if (isGroup) this.group else null
    val dm = if (!isGroup) (this as org.xmtp.android.library.Conversation.Dm).dm else null

    val creatorId = try {
        when {
            group != null -> group.creatorInboxId()
            dm != null -> dm.creatorInboxId()
            else -> ""
        }
    } catch (e: Exception) {
        ""
    }

    val groupName = try {
        group?.name()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    val groupDescription = try {
        group?.description()
    } catch (e: Exception) {
        null
    }

    val groupImageUrl = try {
        group?.imageUrl()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    // Extract invite tag from metadata if this is a group
    val inviteTag = if (group != null) {
        try {
            val conversationGroup = org.xmtp.android.library.Conversation.Group(group)
            val metadata =
                com.naomiplasterer.convos.data.metadata.ConversationMetadataHelper.retrieveMetadata(
                    conversationGroup
                )
            metadata?.tag
        } catch (e: Exception) {
            null
        }
    } else {
        null
    }

    return ConversationEntity(
        id = this.id,
        inboxId = inboxId,
        clientId = "", // Will be set by caller
        topic = this.topic,
        creatorInboxId = creatorId,
        inviteTag = inviteTag,
        consent = "unknown",
        kind = if (isGroup) "group" else "dm",
        name = groupName,
        description = groupDescription,
        imageUrl = groupImageUrl,
        isPinned = false,
        isUnread = false,
        isMuted = false,
        isDraft = false,
        createdAt = this.createdAt.time,
        lastMessageAt = null,
        expiresAt = null // Will be set from metadata if available
    )
}
