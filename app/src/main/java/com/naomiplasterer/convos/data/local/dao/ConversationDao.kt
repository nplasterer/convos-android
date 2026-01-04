package com.naomiplasterer.convos.data.local.dao

import androidx.room.*
import com.naomiplasterer.convos.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations WHERE inboxId = :inboxId AND consent = 'allowed' AND isDraft = 0 AND kind = 'group' AND (expiresAt IS NULL OR expiresAt > :currentTimeMillis) ORDER BY COALESCE(lastMessageAt, createdAt) DESC")
    fun getAllowedConversations(inboxId: String, currentTimeMillis: Long = System.currentTimeMillis()): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE inboxId IN (:inboxIds) AND consent = 'allowed' AND isDraft = 0 AND kind = 'group' AND (expiresAt IS NULL OR expiresAt > :currentTimeMillis) ORDER BY COALESCE(lastMessageAt, createdAt) DESC")
    fun getAllowedConversationsFromAllInboxes(inboxIds: List<String>, currentTimeMillis: Long = System.currentTimeMillis()): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    fun getConversation(conversationId: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversationSync(conversationId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(conversations: List<ConversationEntity>)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("UPDATE conversations SET isPinned = :isPinned WHERE id = :conversationId")
    suspend fun updatePinned(conversationId: String, isPinned: Boolean)

    @Query("UPDATE conversations SET isMuted = :isMuted WHERE id = :conversationId")
    suspend fun updateMuted(conversationId: String, isMuted: Boolean)

    @Query("UPDATE conversations SET isUnread = :isUnread WHERE id = :conversationId")
    suspend fun updateUnread(conversationId: String, isUnread: Boolean)

    @Query("UPDATE conversations SET consent = :consent WHERE id = :conversationId")
    suspend fun updateConsent(conversationId: String, consent: String)

    @Query("UPDATE conversations SET name = :name, description = :description, imageUrl = :imageUrl WHERE id = :conversationId")
    suspend fun updateMetadata(conversationId: String, name: String?, description: String?, imageUrl: String?)

    @Query("UPDATE conversations SET lastMessageAt = :timestamp WHERE id = :conversationId")
    suspend fun updateLastMessageTime(conversationId: String, timestamp: Long)

    @Query("DELETE FROM conversations WHERE inboxId = :inboxId")
    suspend fun deleteAllForInbox(inboxId: String)
}
