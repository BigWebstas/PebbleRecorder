package com.pebblerecorder.app.settings

import android.content.Context

/** "Do Not Disturb while recording": the user's choice, and the filter to restore afterwards. */
object QuietModePrefs {
    private const val PREFS_NAME = "quiet_mode"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SAVED_FILTER = "saved_filter"
    const val NO_SAVED_FILTER = -1

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Kept on disk so a process death mid-recording can't leave Do Not Disturb on for good. */
    fun savedFilter(context: Context): Int = prefs(context).getInt(KEY_SAVED_FILTER, NO_SAVED_FILTER)

    fun setSavedFilter(context: Context, filter: Int) {
        prefs(context).edit().putInt(KEY_SAVED_FILTER, filter).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
