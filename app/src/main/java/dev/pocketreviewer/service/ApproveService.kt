package dev.pocketreviewer.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.pocketreviewer.R
import dev.pocketreviewer.api.ChatContext
import dev.pocketreviewer.api.ChatMessages
import dev.pocketreviewer.messaging.Messaging
import dev.pocketreviewer.api.ReviewManager
import dev.pocketreviewer.util.PullRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ApproveService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prKey  = intent?.getStringExtra(PRNotificationListener.EXTRA_PR_KEY) ?: return START_NOT_STICKY
        val prUrl  = intent.getStringExtra(PRNotificationListener.EXTRA_PR_URL) ?: ""
        val action = intent.getStringExtra("action") ?: "approve"
        val pr     = PullRequest.fromKey(prKey, prUrl)
        val nm     = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager

        val title = if (action == "approve_merge") getString(R.string.notif_approving_merging) else getString(R.string.notif_approving_pr)
        startForeground(prKey.hashCode() + 400,
            NotificationCompat.Builder(this, PRNotificationListener.CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_code)
                .setContentTitle(title)
                .setContentText(prKey)
                .setProgress(0, 0, true)
                .setOngoing(true)
                .build()
        )

        // Rebuild chatContext and reactionName
        val spaceName    = intent?.getStringExtra(PRNotificationListener.EXTRA_SPACE_NAME)
        val messageName  = intent?.getStringExtra(PRNotificationListener.EXTRA_MESSAGE_NAME)
        val threadName   = intent?.getStringExtra(PRNotificationListener.EXTRA_THREAD_NAME)
        val reactionName = intent?.getStringExtra(PRNotificationListener.EXTRA_REACTION_NAME)
        val chatContext  = if (spaceName != null)
            ChatContext(spaceName, messageName, threadName) else null

        scope.launch {
            try {
                if (action == "approve_merge") {
                    ReviewManager.approveAndMergePR(applicationContext, pr)
                    dev.pocketreviewer.data.ReviewHistoryRepository.updateLatestByKey(applicationContext, prKey) {
                        it.copy(wasApproved = true, wasMerged = true)
                    }

                    // Post-merge reactions: remove the active reactions, add the configurable "approved" emoji (AppConfig.emojiApprove)
                    if (chatContext != null) {
                        // The unified Gmail/Chat format carries messageName=null; we derive the real
                        // message from the 👀/active reaction name (…/messages/X/reactions/Y).
                        val target = ManualFlow.reactionTarget(
                            reactionName?.substringBefore("/reactions/"), chatContext.messageName
                        )
                        // Replace the working/verdict reaction with the configured "approved" emoji.
                        if (target != null) runCatching {
                            ReviewManager.swapReaction(applicationContext, target, reactionName, "APPROVE")
                        }
                    }

                    nm.cancel(prKey.hashCode() + 200)
                    nm.cancel(prKey.hashCode() + 400)
                    nm.notify(prKey.hashCode() + 500,
                        NotificationCompat.Builder(this@ApproveService, PRNotificationListener.CHANNEL_STATUS)
                            .setSmallIcon(R.drawable.ic_check)
                            .setContentTitle(getString(R.string.notif_merged))
                            .setContentText(prKey)
                            .setAutoCancel(true)
                            .build()
                    )
                } else {
                    ReviewManager.approvePR(applicationContext, pr)
                    dev.pocketreviewer.data.ReviewHistoryRepository.updateLatestByKey(applicationContext, prKey) {
                        it.copy(wasApproved = true)
                    }
                    nm.cancel(prKey.hashCode() + 200)
                    nm.cancel(prKey.hashCode() + 400)
                    nm.notify(prKey.hashCode() + 500,
                        NotificationCompat.Builder(this@ApproveService, PRNotificationListener.CHANNEL_STATUS)
                            .setSmallIcon(R.drawable.ic_check)
                            .setContentTitle(getString(R.string.notif_pr_approved))
                            .setContentText(prKey)
                            .setAutoCancel(true)
                            .build()
                    )
                }
            } catch (e: ReviewManager.MergeConflictException) {
                nm.cancel(prKey.hashCode() + 400)
                // Reply in the Google Chat thread (unless the message is disabled)
                if (chatContext != null) {
                    val resolved = reactionName?.substringBefore("/reactions/")
                    runCatching {
                        ChatMessages.conflicts(applicationContext)?.let { msg ->
                            Messaging.reply(applicationContext, resolved, chatContext, msg)
                        }
                    }
                }
                nm.notify(prKey.hashCode() + 600,
                    NotificationCompat.Builder(this@ApproveService, PRNotificationListener.CHANNEL_STATUS)
                        .setSmallIcon(R.drawable.ic_dismiss)
                        .setContentTitle(getString(R.string.notif_merge_conflicts_title))
                        .setContentText(getString(R.string.notif_merge_conflicts_text, prKey))
                        .setAutoCancel(true)
                        .build()
                )
            } catch (e: Exception) {
                nm.cancel(prKey.hashCode() + 400)
                nm.notify(prKey.hashCode() + 600,
                    NotificationCompat.Builder(this@ApproveService, PRNotificationListener.CHANNEL_STATUS)
                        .setSmallIcon(R.drawable.ic_dismiss)
                        .setContentTitle(getString(R.string.notif_error_title))
                        .setContentText("${prKey}: ${e.message}")
                        .setAutoCancel(true)
                        .build()
                )
            } finally {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
