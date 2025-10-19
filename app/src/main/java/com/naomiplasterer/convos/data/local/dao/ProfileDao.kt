package com.naomiplasterer.convos.data.local.dao

import androidx.room.*
import com.naomiplasterer.convos.data.local.entity.ProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profiles WHERE inboxId = :inboxId AND conversationId = :conversationId LIMIT 1")
    fun getProfile(inboxId: String, conversationId: String): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE inboxId = :inboxId AND conversationId IS NULL LIMIT 1")
    fun getGlobalProfile(inboxId: String): Flow<ProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ProfileEntity)

    @Update
    suspend fun update(profile: ProfileEntity)

    @Delete
    suspend fun delete(profile: ProfileEntity)
}
