package dev.pocketreviewer.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.pocketreviewer.api.ChatContext
import dev.pocketreviewer.api.ReviewManager
import dev.pocketreviewer.util.PullRequest

class ReviewWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prKey   = inputData.getString(PRNotificationListener.EXTRA_PR_KEY)   ?: return Result.failure()
        val prUrl   = inputData.getString(PRNotificationListener.EXTRA_PR_URL)   ?: ""
        val isMulti = inputData.getBoolean(PRNotificationListener.EXTRA_IS_MULTI_PR, false)

        val spaceName   = inputData.getString(PRNotificationListener.EXTRA_SPACE_NAME)
        val messageName = inputData.getString(PRNotificationListener.EXTRA_MESSAGE_NAME)
        val threadName  = inputData.getString(PRNotificationListener.EXTRA_THREAD_NAME)
        val chatContext = if (spaceName != null)
            ChatContext(spaceName, messageName, threadName) else null

        val pr = PullRequest.fromKey(prKey, prUrl)

        Log.d("ReviewWorker", "Starting review for $prKey")
        PRNotificationListener.appendLog("Worker started for $prKey")

        return try {
            val isAutonomous = ReviewManager.isAutonomousMode(applicationContext)
            PRNotificationListener.appendLog("Mode: ${if (isAutonomous) "autonomous" else "manual"}")

            if (isAutonomous) {
                // Canonical path (same as PRNotificationListener.runAutonomousReview):
                // runReviewFull resolves the message by URL, adds 👀, posts to GitHub, swaps to the
                // verdict emoji and replies in the thread in a chime-safe way. It replaces the
                // divergent addEyesReaction("") that matched the newest message, not the PR.
                val reviewResult = ReviewManager.runReviewFull(applicationContext, pr, chatContext, isMulti)

                if (chatContext != null) {
                    val threadId = chatContext.threadName ?: chatContext.messageName ?: chatContext.spaceName
                    if (reviewResult.leftComments) PRNotificationListener.instance?.watchedThreads?.set(threadId, pr)
                    else PRNotificationListener.instance?.watchedThreads?.remove(threadId)
                }

                // Pass the resolved message + Chat context so the notification's Approve / Approve and merge
                // buttons can add the configurable "approved" emoji (AppConfig.emojiApprove; they used to go without extras).
                PRNotificationListener.instance?.showReviewDoneNotification(
                    pr, reviewResult.verdict, chatContext, reviewResult.resolvedMessageName
                )

            } else {
                // Manual: the review is triggered from History; nothing to do here.
            }

            PRNotificationListener.appendLog("Review completed for $prKey")
            Result.success()

        } catch (e: Exception) {
            Log.e("ReviewWorker", "Error reviewing $prKey: ${e.message}", e)
            PRNotificationListener.appendLog("Error on $prKey: ${e.javaClass.simpleName}: ${e.message}")
            showErrorNotification(prKey, e.message ?: applicationContext.getString(dev.pocketreviewer.R.string.notif_unknown_error))
            Result.failure()
        }
    }

    private fun showErrorNotification(prKey: String, error: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(prKey.hashCode() + 300,
            androidx.core.app.NotificationCompat.Builder(applicationContext, PRNotificationListener.CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(applicationContext.getString(dev.pocketreviewer.R.string.notif_review_error_title))
                .setContentText("$prKey: $error")
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText("$prKey\n$error"))
                .setAutoCancel(true)
                .build()
        )
    }
}
