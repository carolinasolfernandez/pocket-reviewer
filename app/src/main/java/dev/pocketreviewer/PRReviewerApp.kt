package dev.pocketreviewer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.pocketreviewer.service.PRNotificationListener

/**
 * Application entry point.
 *
 * @author Carolina Sol Fernandez <carolinasolfernandez@gmail.com>
 */
class PRReviewerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        nm.createNotificationChannel(NotificationChannel(
            PRNotificationListener.CHANNEL_PENDING,
            getString(R.string.channel_pending_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_pending_desc)
            enableVibration(true)
        })

        nm.createNotificationChannel(NotificationChannel(
            PRNotificationListener.CHANNEL_STATUS,
            getString(R.string.channel_status_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.channel_status_desc)
        })
    }
}
