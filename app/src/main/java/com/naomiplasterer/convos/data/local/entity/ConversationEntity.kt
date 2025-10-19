package com.naomiplasterer.convos.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey

@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = InboxEntity::class,
            parentColumns = ["inboxId"],
            childColumns = ["inboxId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    val inboxId: String,
    val topic: String,
    val creatorInboxId: String,
    val consent: String,
    val kind: String,
    val name: String?,
    val description: String?,
    val imageUrl: String?,
    val isPinned: Boolean = false,
    val isUnread: Boolean = false,
    val isMuted: Boolean = false,
    val isDraft: Boolean = false,
    val createdAt: Long,
    val lastMessageAt: Long?
)
