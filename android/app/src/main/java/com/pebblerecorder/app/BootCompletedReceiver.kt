package com.pebblerecorder.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

private const val NOTIFICATION_CHANNEL_ID = "recording"
private const val REARM_NOTIFICATION_ID = 2

/**
 * Re-arms [PebbleListenerService]'s specialUse foreground service after every reboot, so the
 * persistent "armed" notification is back without the user needing to find and open the app.
 *
 * That alone isn't enough to make the watch trigger itself work again, though: adding the
 * microphone type to the service when a real recording command arrives requires the app to have
 * had genuine foreground UI interaction (opening [MainActivity]) at some point since the process
 * started - the temporary background-start exemption BOOT_COMPLETED itself grants only lasts
 * ~20 seconds, nowhere near long enough to rely on the watch being pressed in time. So this also
 * posts a plain notification prompting a single tap to open the app and permanently clear that
 * restriction until the next reboot.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        ContextCompat.startForegroundService(context, Intent(context, PebbleListenerService::class.java))
        postRearmNotification(context)
    }

    private fun postRearmNotification(context: Context) {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.recording_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.reboot_rearm_notification_title))
            .setContentText(context.getString(R.string.reboot_rearm_notification_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(REARM_NOTIFICATION_ID, notification)
    }
}
