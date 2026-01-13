package com.naomiplasterer.convos.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker
import com.naomiplasterer.convos.data.local.dao.ConversationDao
import com.naomiplasterer.convos.data.local.dao.MessageDao
import com.naomiplasterer.convos.data.repository.ConversationRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val TAG = "ExpiredConversationsWorker"

/**
 * Worker that periodically checks for and cleans up expired conversations.
 * This worker runs in the background and deletes conversations that have passed
 * their expiration time.
 */
@HiltWorker
class ExpiredConversationsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val conversationRepository: ConversationRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting expired conversations cleanup...")

                // Get all expired conversations
                val currentTime = System.currentTimeMillis()
                val expiredConversations = conversationDao.getExpiredConversations(currentTime)

                if (expiredConversations.isEmpty()) {
                    Log.d(TAG, "No expired conversations found")
                    return@withContext Result.success()
                }

                Log.d(TAG, "Found ${expiredConversations.size} expired conversations to clean up")

                // Delete each expired conversation and its messages
                expiredConversations.forEach { conversation ->
                    try {
                        Log.d(TAG, "Deleting expired conversation: ${conversation.id}")

                        // Delete all messages in the conversation
                        messageDao.deleteAllForConversation(conversation.id)

                        // Delete the conversation itself
                        conversationDao.deleteConversation(conversation.id)

                        // Clean up any associated data
                        conversationRepository.deleteConversation(conversation.id)

                        Log.d(TAG, "Successfully deleted conversation: ${conversation.id}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to delete conversation ${conversation.id}", e)
                        // Continue with other conversations even if one fails
                    }
                }

                Log.d(TAG, "Expired conversations cleanup completed successfully")
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "ExpiredConversationsWorker failed", e)

                // Retry if we haven't exceeded max attempts
                if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        }
    }

    companion object {
        private const val WORK_NAME = "ExpiredConversationsCleanup"

        /**
         * Schedules the worker to run periodically every 15 minutes.
         * The worker will also run when the device is charging to conserve battery.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<ExpiredConversationsWorker>(
                repeatInterval = 15,
                repeatIntervalTimeUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            Log.d(TAG, "ExpiredConversationsWorker scheduled")
        }

        /**
         * Runs the worker immediately for testing or on-demand cleanup.
         */
        fun runImmediately(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<ExpiredConversationsWorker>()
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)

            Log.d(TAG, "ExpiredConversationsWorker triggered immediately")
        }

        /**
         * Cancels the periodic work.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "ExpiredConversationsWorker cancelled")
        }
    }
}