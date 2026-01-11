package com.naomiplasterer.convos.data.local.dao

import androidx.room.*
import com.naomiplasterer.convos.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sentAt DESC")
    fun getMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sentAt DESC LIMIT :limit")
    fun getMessagesWithLimit(conversationId: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessage(messageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Update
    suspend fun update(message: MessageEntity)

    @Delete
    suspend fun delete(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteAllForConversation(conversationId: String)

    // Trigger query to observe message changes for a conversation
    // This ensures Room tracks the messages table and triggers UI updates
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    fun observeMessageCount(conversationId: String): Flow<Int>

    // Get the latest message content for a conversation (excluding updates)
    @Query("""
        SELECT content FROM messages
        WHERE conversationId = :conversationId
          AND contentType != 'update'
        ORDER BY sentAt DESC
        LIMIT 1
    """)
    fun getLatestMessageContent(conversationId: String): Flow<String?>

    @Query("""
        SELECT conversationId, MAX(sentAt) as lastMessageTime
        FROM messages
        WHERE conversationId IN (:conversationIds)
        GROUP BY conversationId
    """)
    fun getLastMessageTimes(conversationIds: List<String>): Flow<List<LastMessageInfo>>

    @Query("""
        SELECT m.*
        FROM messages m
        INNER JOIN (
            SELECT conversationId, MAX(sentAt) as maxSentAt
            FROM messages
            WHERE conversationId IN (:conversationIds)
            GROUP BY conversationId
        ) latest ON m.conversationId = latest.conversationId AND m.sentAt = latest.maxSentAt
    """)
    fun getLastMessages(conversationIds: List<String>): Flow<List<MessageEntity>>
}

data class LastMessageInfo(
    val conversationId: String,
    val lastMessageTime: Long?
)
