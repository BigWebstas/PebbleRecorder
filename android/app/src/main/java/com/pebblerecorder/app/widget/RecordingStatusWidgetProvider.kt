package com.pebblerecorder.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.pebblerecorder.app.R
import com.pebblerecorder.app.connectivity.PebbleListenerService
import com.pebblerecorder.app.connectivity.WatchProtocol
import com.pebblerecorder.app.recording.RecordingState
import com.pebblerecorder.app.recording.RecordingStatus
import com.pebblerecorder.app.ui.MainActivity

/**
 * Home-screen widget showing PebbleRecorder's live recording state. The state comes straight from
 * [RecordingStatus] - the same in-memory StateFlow MainActivity shows - so a green tile means
 * PebbleListenerService is recording right now. The OS's own `updatePeriodMillis` floor is 30
 * minutes, too slow to be useful, so PebbleListenerService pushes a refresh directly via
 * [refreshAll] the moment that state changes.
 */
// Don't move or rename this class: Android identifies a placed widget by this exact class name, so
// changing it silently removes every user's widget from their home screen on the next update.
class RecordingStatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> updateOne(context, manager, id) }
    }

    private fun updateOne(context: Context, manager: AppWidgetManager, id: Int) {
        val state = RecordingStatus.state.value
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val labelRes = when (state) {
            RecordingState.RECORDING -> R.string.widget_status_recording
            RecordingState.PAUSED -> R.string.widget_status_paused
            RecordingState.IDLE -> R.string.widget_status_idle
        }
        val dotRes = when (state) {
            RecordingState.RECORDING -> R.drawable.widget_dot_recording
            RecordingState.PAUSED -> R.drawable.widget_dot_paused
            RecordingState.IDLE -> R.drawable.widget_dot_idle
        }
        val views = RemoteViews(context.packageName, R.layout.widget_recording_status).apply {
            setTextViewText(R.id.widget_status_text, context.getString(labelRes))
            setImageViewResource(R.id.widget_status_dot, dotRes)
            setOnClickPendingIntent(R.id.widget_root, openApp)
            // Record when idle, stop otherwise - a tap from the home screen, no need to open the app.
            val recording = state != RecordingState.IDLE
            // A Chronometer counts by itself, so the widget needn't be redrawn every second.
            setViewVisibility(R.id.widget_timer, if (recording) View.VISIBLE else View.GONE)
            if (recording) {
                val elapsedMs = RecordingStatus.progress.value.elapsedSeconds * 1000L
                setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime() - elapsedMs, null, state == RecordingState.RECORDING)
            }
            setImageViewResource(
                R.id.widget_record_button,
                if (recording) R.drawable.widget_ic_stop else R.drawable.widget_ic_record,
            )
            setOnClickPendingIntent(
                R.id.widget_record_button,
                PebbleListenerService.commandPendingIntent(
                    context,
                    if (recording) WatchProtocol.COMMAND_STOP else WatchProtocol.COMMAND_START,
                ),
            )
        }
        manager.updateAppWidget(id, views)
    }

    companion object {
        /** Call right after [RecordingStatus] changes. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, RecordingStatusWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) {
                RecordingStatusWidgetProvider().onUpdate(context, manager, ids)
            }
        }
    }
}
