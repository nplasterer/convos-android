package com.naomiplasterer.convos.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.naomiplasterer.convos.data.local.dao.*
import com.naomiplasterer.convos.data.local.entity.*

@Database(
    entities = [
        InboxEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        MemberEntity::class,
        ProfileEntity::class,
        ReactionEntity::class,
        InviteEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class ConvosDatabase : RoomDatabase() {
    abstract fun inboxDao(): InboxDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memberDao(): MemberDao
    abstract fun profileDao(): ProfileDao
    abstract fun reactionDao(): ReactionDao
}
