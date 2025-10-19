package com.naomiplasterer.convos.data.mapper

import com.naomiplasterer.convos.data.local.entity.ConversationEntity
import com.naomiplasterer.convos.domain.model.Conversation
import com.naomiplasterer.convos.domain.model.ConsentState
import com.naomiplasterer.convos.domain.model.ConversationKind

fun ConversationEntity.toDomain(): Conversation {
    return Conversation(
        id = id,
        inboxId = inboxId,
        topic = topic,
        creatorInboxId = creatorInboxId,
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
        lastMessageAt = lastMessageAt
    )
}

fun Conversation.toEntity(): ConversationEntity {
    return ConversationEntity(
        id = id,
        inboxId = inboxId,
        topic = topic,
        creatorInboxId = creatorInboxId,
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
        lastMessageAt = lastMessageAt
    )
}

suspend fun org.xmtp.android.library.Conversation.toEntity(inboxId: String): ConversationEntity {
    val isGroup = this is org.xmtp.android.library.Conversation.Group
    val group = if (isGroup) (this as org.xmtp.android.library.Conversation.Group).group else null
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

    return ConversationEntity(
        id = this.id,
        inboxId = inboxId,
        topic = this.topic,
        creatorInboxId = creatorId,
        consent = "unknown",
        kind = if (isGroup) "group" else "dm",
        name = group?.name ?: null,
        description = group?.description ?: null,
        imageUrl = null,
        isPinned = false,
        isUnread = false,
        isMuted = false,
        isDraft = false,
        createdAt = this.createdAt.time,
        lastMessageAt = null
    )
}
