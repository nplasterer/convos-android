package com.naomiplasterer.convos.data.mapper

import com.naomiplasterer.convos.data.local.entity.MessageEntity
import com.naomiplasterer.convos.domain.model.Message
import com.naomiplasterer.convos.domain.model.MessageContent
import com.naomiplasterer.convos.domain.model.MessageStatus

fun MessageEntity.toDomain(): Message {
    val content = when (contentType) {
        "text" -> MessageContent.Text(this.content)
        "emoji" -> MessageContent.Emoji(this.content)
        "attachment" -> MessageContent.Attachment(this.content)
        else -> MessageContent.Text(this.content)
    }

    val status = when (this.status) {
        "sending" -> MessageStatus.SENDING
        "sent" -> MessageStatus.SENT
        "delivered" -> MessageStatus.DELIVERED
        "failed" -> MessageStatus.FAILED
        else -> MessageStatus.SENT
    }

    return Message(
        id = id,
        conversationId = conversationId,
        senderInboxId = senderInboxId,
        content = content,
        status = status,
        sentAt = sentAt,
        deliveredAt = deliveredAt,
        replyToId = replyToId
    )
}

fun Message.toEntity(): MessageEntity {
    val contentType = when (content) {
        is MessageContent.Text -> "text"
        is MessageContent.Emoji -> "emoji"
        is MessageContent.Attachment -> "attachment"
        is MessageContent.Update -> "update"
    }

    val contentString = when (content) {
        is MessageContent.Text -> content.text
        is MessageContent.Emoji -> content.emoji
        is MessageContent.Attachment -> content.url
        is MessageContent.Update -> content.details
    }

    val statusString = when (status) {
        MessageStatus.SENDING -> "sending"
        MessageStatus.SENT -> "sent"
        MessageStatus.DELIVERED -> "delivered"
        MessageStatus.FAILED -> "failed"
    }

    return MessageEntity(
        id = id,
        conversationId = conversationId,
        senderInboxId = senderInboxId,
        contentType = contentType,
        content = contentString,
        status = statusString,
        sentAt = sentAt,
        deliveredAt = deliveredAt,
        replyToId = replyToId
    )
}
