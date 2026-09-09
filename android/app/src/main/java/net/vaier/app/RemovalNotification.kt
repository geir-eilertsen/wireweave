package net.vaier.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * The one thing this app ever puts in the status bar: that Vaier let this phone go. It happens while
 * nobody is looking — the connection simply stops working — so a person who is told nothing would just
 * find that their phone had quietly stopped reaching anything.
 */
class RemovalNotification(context: Context) {

    private val app = context.applicationContext
    private val manager = app.getSystemService(NotificationManager::class.java)

    /** Posts nothing at all if the person turned notifications down; being connected never depended on it. */
    fun post(deviceName: String) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Vaier", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = StandingWatch.removalText(deviceName)
        manager.notify(
            ID,
            NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(StandingWatch.NOTIFICATION_TITLE)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL = "vaier"
        const val ID = 1
    }
}
