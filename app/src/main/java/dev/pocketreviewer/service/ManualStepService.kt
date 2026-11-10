package dev.pocketreviewer.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.pocketreviewer.R
import dev.pocketreviewer.service.ManualFlow.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs ONE manual step durably (survives leaving the app). Shows only an ongoing
 * progress notification — no action buttons. The result lands in the History entry
 * (the dialog auto-refreshes via ReviewHistoryRepository.changes).
 */
class ManualStepService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prKey  = intent?.getStringExtra(PRNotificationListener.EXTRA_PR_KEY) ?: return START_NOT_STICKY
        val prUrl  = intent.getStringExtra(PRNotificationListener.EXTRA_PR_URL) ?: ""
        val stage  = ManualFlow.parseStage(intent.getStringExtra(EXTRA_STAGE))
        val action = intent.getStringExtra(EXTRA_ACTION) ?: ACTION_CONFIRM

        val label = if (action == ACTION_APPROVE_MERGE) getString(R.string.notif_manual_approving_merging, prKey) else when (stage) {
            Stage.REVIEW      -> getString(R.string.notif_manual_reviewing, prKey)
            Stage.APPROVE     -> getString(R.string.notif_manual_approving, prKey)
            Stage.MERGE       -> getString(R.string.notif_manual_merging, prKey)
            Stage.POST_GITHUB -> getString(R.string.notif_manual_posting)
            Stage.CHAT_REPLY  -> getString(R.string.notif_manual_replying)
            Stage.DONE        -> getString(R.string.notif_manual_done)
        }
        startForeground(
            prKey.hashCode() + 800,
            NotificationCompat.Builder(this, PRNotificationListener.CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_code)
                .setContentTitle(label)
                .setContentText(prKey)
                .setProgress(0, 0, true)
                .setOngoing(true)
                .build()
        )

        scope.launch {
            try {
                when (action) {
                    ACTION_SKIP          -> ManualModeOrchestrator.skipStep(applicationContext, prKey, stage)
                    ACTION_APPROVE_MERGE -> ManualModeOrchestrator.runApproveMerge(applicationContext, prKey)
                    else                 -> ManualModeOrchestrator.runStep(applicationContext, prKey, stage)
                }
            } finally {
                (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .cancel(prKey.hashCode() + 800)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_STAGE  = "manual_stage"
        const val EXTRA_ACTION = "manual_action"
        const val ACTION_CONFIRM = "confirm"
        const val ACTION_SKIP    = "skip"
        const val ACTION_APPROVE_MERGE = "approve_merge"

        fun start(context: Context, prKey: String, prUrl: String, stage: Stage, action: String) {
            val intent = Intent(context, ManualStepService::class.java).apply {
                putExtra(PRNotificationListener.EXTRA_PR_KEY, prKey)
                putExtra(PRNotificationListener.EXTRA_PR_URL, prUrl)
                putExtra(EXTRA_STAGE, stage.name)
                putExtra(EXTRA_ACTION, action)
            }
            context.startForegroundService(intent)
        }
    }
}
