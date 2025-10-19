package com.naomiplasterer.convos.data.local.dao

import androidx.room.*
import com.naomiplasterer.convos.data.local.entity.MemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemberDao {

    @Query("SELECT * FROM members WHERE conversationId = :conversationId")
    fun getMembers(conversationId: String): Flow<List<MemberEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(member: MemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(members: List<MemberEntity>)

    @Delete
    suspend fun delete(member: MemberEntity)

    @Query("DELETE FROM members WHERE conversationId = :conversationId")
    suspend fun deleteAllForConversation(conversationId: String)
}
