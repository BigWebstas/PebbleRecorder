package com.pebblerecorder.app.transcription

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pebblerecorder.app.R
import com.pebblerecorder.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val PROGRESS_CHANNEL_ID = "transcription_progress"
private const val PROGRESS_NOTIFICATION_ID = 90

/**
 * A notification with a progress bar while the on-device engine transcribes. It's on its own
 * channel at default importance, so it makes a sound when it appears (the "Transcription"
 * channel's notices are all silent); the percent updates after that don't re-alert
 * ([NotificationCompat.Builder.setOnlyAlertOnce]), or it would chime on every step. It goes away
 * when transcription ends - the "transcript ready" / "failed" notice takes over from there.
 */
internal class TranscriptionProgressNotification(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                PROGRESS_CHANNEL_ID,
                context.getString(R.string.transcription_progress_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    /** Follows [TranscriptionStatus] for as long as [scope] lives. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            TranscriptionStatus.active.collect { show(it) }
        }
    }

    private fun show(active: TranscriptionStatus.Active?) {
        if (active == null) {
            manager.cancel(PROGRESS_NOTIFICATION_ID)
            return
        }
        if (!canNotify()) return
        val percent = active.percent
        val notification = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.transcription_progress_title))
            .setContentText(
                if (percent == null) {
                    context.getString(R.string.transcription_progress_preparing)
                } else {
                    context.getString(R.string.transcription_progress_percent, percent)
                },
            )
            .setProgress(100, percent ?: 0, percent == null)
            // The system keeps the elapsed time ticking, so it needn't be re-posted every second.
            .setUsesChronometer(true)
            .setWhen(active.startedAtMs)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        manager.notify(PROGRESS_NOTIFICATION_ID, notification)
    }

    private fun canNotify() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}
