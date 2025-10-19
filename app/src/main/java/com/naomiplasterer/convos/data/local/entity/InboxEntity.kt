package com.naomiplasterer.convos.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inboxes")
data class InboxEntity(
    @PrimaryKey
    val inboxId: String,
    val address: String,
    val createdAt: Long
)
