package com.pebblerecorder.app.recording

import android.app.NotificationManager
import android.content.Context
import com.pebblerecorder.app.settings.QuietModePrefs

/**
 * Switches the phone to "alarms only" Do Not Disturb while a recording runs, then puts the
 * previous interruption filter back. Needs the user to have granted Do Not Disturb access
 * (see [hasAccess]); without it, or with the setting off, this does nothing.
 */
class QuietMode(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun enter() {
        if (!QuietModePrefs.isEnabled(context) || !hasAccess(context)) return
        if (QuietModePrefs.savedFilter(context) != QuietModePrefs.NO_SAVED_FILTER) return // already entered
        val current = manager.currentInterruptionFilter
        // Already as quiet or quieter than we'd make it (alarms only / total silence): leave it be.
        if (current == NotificationManager.INTERRUPTION_FILTER_ALARMS ||
            current == NotificationManager.INTERRUPTION_FILTER_NONE ||
            current == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        ) {
            return
        }
        QuietModePrefs.setSavedFilter(context, current)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
    }

    /** Restores what [enter] changed. Also safe at service start, to undo a previous process's leftover. */
    fun exit() {
        val saved = QuietModePrefs.savedFilter(context)
        if (saved == QuietModePrefs.NO_SAVED_FILTER) return
        QuietModePrefs.setSavedFilter(context, QuietModePrefs.NO_SAVED_FILTER)
        if (hasAccess(context)) manager.setInterruptionFilter(saved)
    }

    companion object {
        fun hasAccess(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    }
}
