package com.naomiplasterer.convos.data.local.dao

import androidx.room.*
import com.naomiplasterer.convos.data.local.entity.ReactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReactionDao {

    @Query("SELECT * FROM reactions WHERE messageId = :messageId")
    fun getReactions(messageId: String): Flow<List<ReactionEntity>>

    @Query("SELECT * FROM reactions WHERE messageId = :messageId")
    fun getReactionsForMessage(messageId: String): Flow<List<ReactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reaction: ReactionEntity)

    @Delete
    suspend fun delete(reaction: ReactionEntity)

    @Query("DELETE FROM reactions WHERE id = :reactionId")
    suspend fun deleteById(reactionId: Long)

    @Query("DELETE FROM reactions WHERE messageId = :messageId")
    suspend fun deleteAllForMessage(messageId: String)
}
