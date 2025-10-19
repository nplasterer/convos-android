package com.naomiplasterer.convos.domain.model

data class Conversation(
    val id: String,
    val inboxId: String,
    val topic: String,
    val creatorInboxId: String,
    val consent: ConsentState,
    val kind: ConversationKind,
    val name: String?,
    val description: String?,
    val imageUrl: String?,
    val isPinned: Boolean = false,
    val isUnread: Boolean = false,
    val isMuted: Boolean = false,
    val isDraft: Boolean = false,
    val createdAt: Long,
    val lastMessageAt: Long?,
    val lastMessagePreview: String? = null,
    val members: List<Member> = emptyList()
)

enum class ConsentState {
    ALLOWED,
    DENIED,
    UNKNOWN
}

enum class ConversationKind {
    GROUP,
    DM
}
