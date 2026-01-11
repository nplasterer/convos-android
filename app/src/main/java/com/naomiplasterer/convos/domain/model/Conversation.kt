package com.naomiplasterer.convos.domain.model

data class Conversation(
    val id: String,
    val inboxId: String,
    val clientId: String,
    val topic: String,
    val creatorInboxId: String,
    val inviteTag: String?,
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
    val expiresAt: Long?,
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

/**
 * Check if two conversations are meaningfully equal (ignoring timestamps and preview).
 * This is used to prevent unnecessary UI updates when only timestamps change.
 */
fun Conversation.meaningfullyEquals(other: Conversation?): Boolean {
    if (other == null) return false

    return id == other.id &&
            inboxId == other.inboxId &&
            clientId == other.clientId &&
            topic == other.topic &&
            creatorInboxId == other.creatorInboxId &&
            inviteTag == other.inviteTag &&
            consent == other.consent &&
            kind == other.kind &&
            name == other.name &&
            description == other.description &&
            imageUrl == other.imageUrl &&
            isPinned == other.isPinned &&
            isUnread == other.isUnread &&
            isMuted == other.isMuted &&
            isDraft == other.isDraft &&
            expiresAt == other.expiresAt &&
            members == other.members
    // Intentionally excluded: createdAt, lastMessageAt, lastMessagePreview
}

/**
 * Check if two lists of conversations are meaningfully equal.
 * Used to prevent unnecessary UI updates in the conversations list.
 *
 * IMPORTANT: This checks both content AND order. If conversations change order
 * (e.g., due to new messages), this will return false to trigger a UI update.
 */
fun List<Conversation>.meaningfullyEquals(other: List<Conversation>?): Boolean {
    if (other == null) return false
    if (this.size != other.size) return false

    // First check if the order is the same by comparing IDs in sequence
    // This ensures we detect when conversations re-order due to new messages
    for (i in indices) {
        if (this[i].id != other[i].id) {
            // Order has changed - this is a meaningful difference
            return false
        }
    }

    // Order is the same, now check if individual conversations have meaningful changes
    // Create maps by ID for efficient lookup
    val thisMap = this.associateBy { it.id }
    val otherMap = other.associateBy { it.id }

    // Check if all IDs match
    if (thisMap.keys != otherMap.keys) return false

    // Check if each conversation is meaningfully equal
    return thisMap.all { (id, conversation) ->
        conversation.meaningfullyEquals(otherMap[id])
    }
}
