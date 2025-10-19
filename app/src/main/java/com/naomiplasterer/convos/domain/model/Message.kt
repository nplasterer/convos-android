package com.naomiplasterer.convos.domain.model

data class Message(
    val id: String,
    val conversationId: String,
    val senderInboxId: String,
    val content: MessageContent,
    val status: MessageStatus,
    val sentAt: Long,
    val deliveredAt: Long?,
    val replyToId: String? = null,
    val reactions: List<Reaction> = emptyList()
)

sealed class MessageContent {
    data class Text(val text: String) : MessageContent()
    data class Emoji(val emoji: String) : MessageContent()
    data class Attachment(val url: String) : MessageContent()
    data class Update(val updateType: String, val details: String) : MessageContent()
}

enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    FAILED
}
