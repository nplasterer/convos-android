package com.naomiplasterer.convos.data.repository

import com.naomiplasterer.convos.data.local.dao.MessageDao
import com.naomiplasterer.convos.data.mapper.toDomain
import com.naomiplasterer.convos.data.mapper.toEntity
import com.naomiplasterer.convos.data.xmtp.XMTPClientManager
import com.naomiplasterer.convos.domain.model.Message
import com.naomiplasterer.convos.domain.model.MessageContent
import com.naomiplasterer.convos.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val xmtpClientManager: XMTPClientManager
) {

    fun getMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessages(conversationId)
            .map { entities -> entities.map { it.toDomain() } }
    }

    suspend fun syncMessages(inboxId: String, conversationId: String): Result<Unit> {
        return try {
            val client = xmtpClientManager.getClient(inboxId)
                ?: return Result.failure(IllegalStateException("No client for inbox: $inboxId"))

            val conversation = client.conversations.findConversation(conversationId)
                ?: return Result.failure(IllegalStateException("Conversation not found: $conversationId"))

            conversation.sync()

            val messages = conversation.messages()

            val entities = messages.map { decodedMessage ->
                com.naomiplasterer.convos.data.local.entity.MessageEntity(
                    id = decodedMessage.id,
                    conversationId = conversationId,
                    senderInboxId = decodedMessage.senderInboxId,
                    contentType = "text",
                    content = decodedMessage.body,
                    status = "sent",
                    sentAt = decodedMessage.sentAtNs / 1_000_000,
                    deliveredAt = null,
                    replyToId = null
                )
            }

            messageDao.insertAll(entities)

            Timber.d("Synced ${entities.size} messages for conversation: $conversationId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync messages")
            Result.failure(e)
        }
    }

    suspend fun sendMessage(
        inboxId: String,
        conversationId: String,
        text: String
    ): Result<String> {
        return try {
            val client = xmtpClientManager.getClient(inboxId)
                ?: return Result.failure(IllegalStateException("No client for inbox: $inboxId"))

            val conversation = client.conversations.findConversation(conversationId)
                ?: return Result.failure(IllegalStateException("Conversation not found: $conversationId"))

            val localMessage = Message(
                id = "temp_${System.currentTimeMillis()}",
                conversationId = conversationId,
                senderInboxId = inboxId,
                content = MessageContent.Text(text),
                status = MessageStatus.SENDING,
                sentAt = System.currentTimeMillis(),
                deliveredAt = null
            )

            messageDao.insert(localMessage.toEntity())

            val messageId = conversation.send(text)

            messageDao.updateStatus(localMessage.id, "sent")

            syncMessages(inboxId, conversationId)

            Timber.d("Sent message: $messageId")
            Result.success(messageId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send message")
            Result.failure(e)
        }
    }
}
