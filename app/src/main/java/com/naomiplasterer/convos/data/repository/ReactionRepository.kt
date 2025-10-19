package com.naomiplasterer.convos.data.repository

import com.naomiplasterer.convos.data.local.dao.ReactionDao
import com.naomiplasterer.convos.data.local.entity.ReactionEntity
import com.naomiplasterer.convos.domain.model.Reaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReactionRepository @Inject constructor(
    private val reactionDao: ReactionDao
) {

    fun getReactionsForMessage(messageId: String): Flow<List<Reaction>> {
        return reactionDao.getReactionsForMessage(messageId)
            .map { entities ->
                entities.map { entity ->
                    Reaction(
                        id = entity.id.toString(),
                        messageId = entity.messageId,
                        senderInboxId = entity.senderInboxId,
                        emoji = entity.emoji,
                        createdAt = entity.createdAt
                    )
                }
            }
    }

    suspend fun addReaction(
        messageId: String,
        senderInboxId: String,
        emoji: String
    ): Result<Unit> {
        return try {
            val reaction = ReactionEntity(
                messageId = messageId,
                senderInboxId = senderInboxId,
                emoji = emoji,
                createdAt = System.currentTimeMillis()
            )
            reactionDao.insert(reaction)
            Timber.d("Reaction added: $emoji to message $messageId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to add reaction")
            Result.failure(e)
        }
    }

    suspend fun removeReaction(reactionId: String): Result<Unit> {
        return try {
            reactionDao.deleteById(reactionId.toLong())
            Timber.d("Reaction removed: $reactionId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove reaction")
            Result.failure(e)
        }
    }
}
