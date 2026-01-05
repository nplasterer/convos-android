package com.naomiplasterer.convos.di

import android.content.Context
import androidx.room.Room
import com.naomiplasterer.convos.data.local.ConvosDatabase
import com.naomiplasterer.convos.data.local.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideConvosDatabase(
        @ApplicationContext context: Context
    ): ConvosDatabase {
        return Room.databaseBuilder(
            context,
            ConvosDatabase::class.java,
            "convos_database"
        )
            .fallbackToDestructiveMigration(false)
            .build()
    }

    @Provides
    fun provideInboxDao(database: ConvosDatabase): InboxDao = database.inboxDao()

    @Provides
    fun provideConversationDao(database: ConvosDatabase): ConversationDao =
        database.conversationDao()

    @Provides
    fun provideMessageDao(database: ConvosDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideMemberProfileDao(database: ConvosDatabase): MemberProfileDao =
        database.memberProfileDao()
}
