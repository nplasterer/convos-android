package com.naomiplasterer.convos.data.local.dao

import androidx.room.*
import com.naomiplasterer.convos.data.local.entity.ConversationEntity
import com.naomiplasterer.convos.data.local.entity.ConversationWithMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    // Room doesn't automatically track tables in subqueries, so we need to explicitly
    // reference the messages table to ensure Room re-runs this query when messages change
    @Query("""
        SELECT
            c.*,
            (SELECT m.content
             FROM messages m
             WHERE m.conversationId = c.id
               AND m.contentType != 'update'
             ORDER BY m.sentAt DESC
             LIMIT 1) as lastMessagePreview
        FROM conversations c
        WHERE c.inboxId = :inboxId
          AND c.consent = 'allowed'
          AND c.isDraft = 0
          AND c.kind = 'group'
          AND (c.expiresAt IS NULL OR c.expiresAt > :currentTimeMillis)
        ORDER BY COALESCE(c.lastMessageAt, c.createdAt) DESC
    """)
    fun getAllowedConversations(
        inboxId: String,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): Flow<List<ConversationWithMessage>>

    // Room doesn't automatically track tables in subqueries, so we need to explicitly
    // reference the messages table to ensure Room re-runs this query when messages change
    @Query("""
        SELECT
            c.*,
            (SELECT m.content
             FROM messages m
             WHERE m.conversationId = c.id
               AND m.contentType != 'update'
             ORDER BY m.sentAt DESC
             LIMIT 1) as lastMessagePreview
        FROM conversations c
        WHERE c.inboxId IN (:inboxIds)
          AND c.consent = 'allowed'
          AND c.isDraft = 0
          AND c.kind = 'group'
          AND (c.expiresAt IS NULL OR c.expiresAt > :currentTimeMillis)
        ORDER BY COALESCE(c.lastMessageAt, c.createdAt) DESC
    """)
    fun getAllowedConversationsFromAllInboxes(
        inboxIds: List<String>,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): Flow<List<ConversationWithMessage>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    fun getConversation(conversationId: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversationSync(conversationId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE inviteTag = :tag LIMIT 1")
    suspend fun findConversationByTag(tag: String): ConversationEntity?

    @Query("SELECT id FROM conversations WHERE inviteTag = :tag AND isDraft = 0 LIMIT 1")
    fun observeConversationByTag(tag: String): Flow<String?>

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
    suspend fun updateMetadata(
        conversationId: String,
        name: String?,
        description: String?,
        imageUrl: String?
    )

    @Query("UPDATE conversations SET lastMessageAt = :timestamp WHERE id = :conversationId")
    suspend fun updateLastMessageTime(conversationId: String, timestamp: Long)

    @Query("DELETE FROM conversations WHERE inboxId = :inboxId")
    suspend fun deleteAllForInbox(inboxId: String)
}
