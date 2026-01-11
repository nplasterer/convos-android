package com.naomiplasterer.convos.data.local.entity

// Room will map the columns from the query directly to these properties
data class ConversationWithMessage(
    // All the ConversationEntity fields (Room will map them automatically)
    val id: String,
    val inboxId: String,
    val clientId: String,
    val topic: String,
    val creatorInboxId: String,
    val inviteTag: String?,
    val consent: String,
    val kind: String,
    val name: String?,
    val description: String?,
    val imageUrl: String?,
    val isPinned: Boolean,
    val isUnread: Boolean,
    val isMuted: Boolean,
    val isDraft: Boolean,
    val createdAt: Long,
    val lastMessageAt: Long?,
    val expiresAt: Long?,
    // Additional field from the subquery
    val lastMessagePreview: String?
) {
    // Helper to convert to ConversationEntity
    fun toConversationEntity(): ConversationEntity {
        return ConversationEntity(
            id = id,
            inboxId = inboxId,
            clientId = clientId,
            topic = topic,
            creatorInboxId = creatorInboxId,
            inviteTag = inviteTag,
            consent = consent,
            kind = kind,
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
}