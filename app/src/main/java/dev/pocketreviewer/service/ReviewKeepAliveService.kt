package dev.pocketreviewer.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.pocketreviewer.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service whose ONLY purpose is to keep the process alive while at least one
 * autonomous review is running. An autonomous review lasts up to ~22 min (initial delay 2–5 min
 * + review + CI wait of up to 15 min) and lives in the [PRNotificationListener] scope;
 * without a foreground service, the OS (Samsung in particular) can kill the process when
 * the app is closed and cut the review off halfway. This service only raises the process
 * priority — it runs no review logic.
 *
 * Refcount: it starts with the first in-flight review and stops with the last one, so several
 * concurrent PRs share a single FGS.
 *
 * Starting an FGS from the background (the listener) requires, on Android 12+, that the app be
 * exempt from battery optimization (Doze allowlist). If it is not, [acquire] catches
 * the exception and we carry on best-effort in the listener scope (same as before).
 */
class ReviewKeepAliveService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        // The review lives in the listener, not here: if the OS kills us, there is no point
        // in reviving just this service.
        return START_NOT_STICKY
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, PRNotificationListener.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_code)
            .setContentTitle(getString(R.string.notif_keepalive_title))
            .setContentText(getString(R.string.notif_keepalive_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ReviewKeepAlive"
        private const val NOTIF_ID = 424242
        private val active = AtomicInteger(0)

        /**
         * Increments the refcount; starts the FGS if this is the first in-flight review. Best-effort:
         * if the OS blocks the background start (no battery exemption), it logs and carries
         * on — the counter stays consistent and [release] will do a no-op stopService.
         */
        fun acquire(context: Context) {
            if (active.getAndIncrement() == 0) {
                runCatching {
                    context.startForegroundService(Intent(context, ReviewKeepAliveService::class.java))
                }.onFailure {
                    Log.w(TAG, "Could not start the keep-alive FGS (missing battery exemption?): ${it.message}")
                }
            }
        }

        /** Decrements the refcount; stops the FGS when no review is left in flight. */
        fun release(context: Context) {
            if (active.decrementAndGet() <= 0) {
                active.set(0)
                runCatching { context.stopService(Intent(context, ReviewKeepAliveService::class.java)) }
            }
        }
    }
}
