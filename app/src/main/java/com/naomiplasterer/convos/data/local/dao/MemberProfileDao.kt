package com.naomiplasterer.convos.data.local.dao

import androidx.room.*
import com.naomiplasterer.convos.data.local.entity.MemberProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemberProfileDao {

    @Query("SELECT * FROM member_profiles WHERE conversationId = :conversationId AND inboxId = :inboxId")
    suspend fun getProfile(conversationId: String, inboxId: String): MemberProfileEntity?

    @Query("SELECT * FROM member_profiles WHERE conversationId = :conversationId")
    suspend fun getProfilesForConversation(conversationId: String): List<MemberProfileEntity>

    @Query("SELECT * FROM member_profiles WHERE conversationId = :conversationId")
    fun getProfilesForConversationFlow(conversationId: String): Flow<List<MemberProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: MemberProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(profiles: List<MemberProfileEntity>)

    @Update
    suspend fun update(profile: MemberProfileEntity)

    @Delete
    suspend fun delete(profile: MemberProfileEntity)

    @Query("UPDATE member_profiles SET name = :name WHERE conversationId = :conversationId AND inboxId = :inboxId")
    suspend fun updateName(conversationId: String, inboxId: String, name: String?)

    @Query("UPDATE member_profiles SET avatar = :avatar WHERE conversationId = :conversationId AND inboxId = :inboxId")
    suspend fun updateAvatar(conversationId: String, inboxId: String, avatar: String?)

    @Query("DELETE FROM member_profiles WHERE conversationId = :conversationId")
    suspend fun deleteAllForConversation(conversationId: String)
}
