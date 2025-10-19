package com.naomiplasterer.convos.domain.model

data class Profile(
    val inboxId: String,
    val conversationId: String?,
    val displayName: String?,
    val avatarUrl: String?
)
