package com.naomiplasterer.convos.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "member_profiles",
    primaryKeys = ["conversationId", "inboxId"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["inboxId"]),
        Index(value = ["conversationId", "inboxId"], unique = true)
    ]
)
data class MemberProfileEntity(
    val conversationId: String,
    val inboxId: String,
    val name: String?,
    val avatar: String?
)
