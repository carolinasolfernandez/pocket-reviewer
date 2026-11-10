package dev.pocketreviewer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.pocketreviewer.api.ChatContext
import dev.pocketreviewer.util.PullRequest

class ReviewActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prKey = intent.getStringExtra(PRNotificationListener.EXTRA_PR_KEY) ?: return
        val prUrl = intent.getStringExtra(PRNotificationListener.EXTRA_PR_URL) ?: ""

        when (intent.action) {
            PRNotificationListener.ACTION_CONFIRM -> {
                Log.d("ReviewReceiver", "Confirmed: $prKey — enqueuing WorkManager")
                PRNotificationListener.appendLog("CONFIRM received for $prKey")

                val dataBuilder = Data.Builder()
                    .putString(PRNotificationListener.EXTRA_PR_KEY, prKey)
                    .putString(PRNotificationListener.EXTRA_PR_URL, prUrl)
                    .putString(PRNotificationListener.EXTRA_MSG_ID, intent.getStringExtra(PRNotificationListener.EXTRA_MSG_ID))
                    .putBoolean(PRNotificationListener.EXTRA_IS_MULTI_PR, intent.getBooleanExtra(PRNotificationListener.EXTRA_IS_MULTI_PR, false))

                intent.getStringExtra(PRNotificationListener.EXTRA_SPACE_NAME)?.let { dataBuilder.putString(PRNotificationListener.EXTRA_SPACE_NAME, it) }
                intent.getStringExtra(PRNotificationListener.EXTRA_MESSAGE_NAME)?.let { dataBuilder.putString(PRNotificationListener.EXTRA_MESSAGE_NAME, it) }
                intent.getStringExtra(PRNotificationListener.EXTRA_THREAD_NAME)?.let { dataBuilder.putString(PRNotificationListener.EXTRA_THREAD_NAME, it) }

                val workRequest = OneTimeWorkRequestBuilder<ReviewWorker>()
                    .setInputData(dataBuilder.build())
                    .build()
                WorkManager.getInstance(context).enqueue(workRequest)
            }

            PRNotificationListener.ACTION_DISMISS -> {
                Log.d("ReviewReceiver", "Dismissed: $prKey")
            }

            PRNotificationListener.ACTION_REREVIEW -> {
                Log.d("ReviewReceiver", "Re-review: $prKey")
                val dataBuilder = Data.Builder()
                    .putString(PRNotificationListener.EXTRA_PR_KEY, prKey)
                    .putString(PRNotificationListener.EXTRA_PR_URL, prUrl)
                intent.getStringExtra(PRNotificationListener.EXTRA_SPACE_NAME)?.let { dataBuilder.putString(PRNotificationListener.EXTRA_SPACE_NAME, it) }
                intent.getStringExtra(PRNotificationListener.EXTRA_MESSAGE_NAME)?.let { dataBuilder.putString(PRNotificationListener.EXTRA_MESSAGE_NAME, it) }
                intent.getStringExtra(PRNotificationListener.EXTRA_THREAD_NAME)?.let { dataBuilder.putString(PRNotificationListener.EXTRA_THREAD_NAME, it) }
                val workRequest = OneTimeWorkRequestBuilder<ReviewWorker>()
                    .setInputData(dataBuilder.build())
                    .build()
                WorkManager.getInstance(context).enqueue(workRequest)
            }

            PRNotificationListener.ACTION_APPROVE -> {
                Log.d("ReviewReceiver", "Approve: $prKey")
                val pr = PullRequest.fromKey(prKey, prUrl)
                val serviceIntent = Intent(context, ApproveService::class.java).apply {
                    putExtra(PRNotificationListener.EXTRA_PR_KEY, prKey)
                    putExtra(PRNotificationListener.EXTRA_PR_URL, prUrl)
                    putExtra("action", "approve")
                }
                context.startForegroundService(serviceIntent)
            }

            PRNotificationListener.ACTION_APPROVE_MERGE -> {
                Log.d("ReviewReceiver", "Approve and merge: $prKey")
                val serviceIntent = Intent(context, ApproveService::class.java).apply {
                    putExtra(PRNotificationListener.EXTRA_PR_KEY, prKey)
                    putExtra(PRNotificationListener.EXTRA_PR_URL, prUrl)
                    putExtra("action", "approve_merge")
                    // Pass chatContext and reactionName for the post-merge reactions
                    intent.getStringExtra(PRNotificationListener.EXTRA_SPACE_NAME)?.let { putExtra(PRNotificationListener.EXTRA_SPACE_NAME, it) }
                    intent.getStringExtra(PRNotificationListener.EXTRA_MESSAGE_NAME)?.let { putExtra(PRNotificationListener.EXTRA_MESSAGE_NAME, it) }
                    intent.getStringExtra(PRNotificationListener.EXTRA_THREAD_NAME)?.let { putExtra(PRNotificationListener.EXTRA_THREAD_NAME, it) }
                    intent.getStringExtra(PRNotificationListener.EXTRA_REACTION_NAME)?.let { putExtra(PRNotificationListener.EXTRA_REACTION_NAME, it) }
                }
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
