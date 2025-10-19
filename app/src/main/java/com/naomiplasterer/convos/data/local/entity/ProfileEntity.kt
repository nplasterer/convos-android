package com.naomiplasterer.convos.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val inboxId: String,
    val conversationId: String?,
    val displayName: String?,
    val avatarUrl: String?
)
