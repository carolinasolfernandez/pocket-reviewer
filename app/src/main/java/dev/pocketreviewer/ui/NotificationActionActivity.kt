package dev.pocketreviewer.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.pocketreviewer.service.PRNotificationListener
import dev.pocketreviewer.service.ReviewWorker
import dev.pocketreviewer.util.PullRequest

/**
 * Transparent trampoline activity that dispatches notification actions.
 * Samsung One UI blocks PendingIntent.getBroadcast from a NotificationListenerService,
 * but PendingIntent.getActivity always works.
 * This activity shows no UI — it processes the intent and finishes immediately.
 */
class NotificationActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action ?: return
        val prKey = intent.getStringExtra(PRNotificationListener.EXTRA_PR_KEY) ?: return
        val prUrl = intent.getStringExtra(PRNotificationListener.EXTRA_PR_URL) ?: ""

        Log.d("NotifAction", "Action=$action prKey=$prKey")
        PRNotificationListener.appendLog("Action: $action for $prKey")

        when (action) {
            PRNotificationListener.ACTION_CONFIRM,
            PRNotificationListener.ACTION_REREVIEW -> {
                Log.d("NotifAction", "Enqueuing review for $prKey")
                val dataBuilder = Data.Builder()
                    .putString(PRNotificationListener.EXTRA_PR_KEY, prKey)
                    .putString(PRNotificationListener.EXTRA_PR_URL, prUrl)
                    .putString(PRNotificationListener.EXTRA_MSG_ID,
                        intent.getStringExtra(PRNotificationListener.EXTRA_MSG_ID))
                    .putBoolean(PRNotificationListener.EXTRA_IS_MULTI_PR,
                        intent.getBooleanExtra(PRNotificationListener.EXTRA_IS_MULTI_PR, false))

                intent.getStringExtra(PRNotificationListener.EXTRA_SPACE_NAME)
                    ?.let { dataBuilder.putString(PRNotificationListener.EXTRA_SPACE_NAME, it) }
                intent.getStringExtra(PRNotificationListener.EXTRA_MESSAGE_NAME)
                    ?.let { dataBuilder.putString(PRNotificationListener.EXTRA_MESSAGE_NAME, it) }
                intent.getStringExtra(PRNotificationListener.EXTRA_THREAD_NAME)
                    ?.let { dataBuilder.putString(PRNotificationListener.EXTRA_THREAD_NAME, it) }

                val workRequest = OneTimeWorkRequestBuilder<ReviewWorker>()
                    .setInputData(dataBuilder.build())
                    .build()
                WorkManager.getInstance(this).enqueue(workRequest)
            }

            PRNotificationListener.ACTION_DISMISS -> {
                Log.d("NotifAction", "Dismissing $prKey")
                // Dismiss only — the notification was already cancelled via FLAG_AUTO_CANCEL
            }

            PRNotificationListener.ACTION_APPROVE,
            PRNotificationListener.ACTION_APPROVE_MERGE -> {
                // Delegate to the approve service
                val serviceIntent = Intent(this,
                    dev.pocketreviewer.service.ApproveService::class.java).apply {
                    putExtra(PRNotificationListener.EXTRA_PR_KEY, prKey)
                    putExtra(PRNotificationListener.EXTRA_PR_URL, prUrl)
                    putExtra("action", if (action == PRNotificationListener.ACTION_APPROVE)
                        "approve" else "approve_merge")
                    intent.getStringExtra(PRNotificationListener.EXTRA_SPACE_NAME)
                        ?.let { putExtra(PRNotificationListener.EXTRA_SPACE_NAME, it) }
                    intent.getStringExtra(PRNotificationListener.EXTRA_MESSAGE_NAME)
                        ?.let { putExtra(PRNotificationListener.EXTRA_MESSAGE_NAME, it) }
                    intent.getStringExtra(PRNotificationListener.EXTRA_THREAD_NAME)
                        ?.let { putExtra(PRNotificationListener.EXTRA_THREAD_NAME, it) }
                    intent.getStringExtra(PRNotificationListener.EXTRA_REACTION_NAME)
                        ?.let { putExtra(PRNotificationListener.EXTRA_REACTION_NAME, it) }
                }
                startForegroundService(serviceIntent)
            }
        }
    }
}
