package com.naomiplasterer.convos.domain.model

data class Reaction(
    val id: String,
    val messageId: String,
    val senderInboxId: String,
    val emoji: String,
    val createdAt: Long
)
