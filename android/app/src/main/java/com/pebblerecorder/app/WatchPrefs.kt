package com.pebblerecorder.app

import android.content.Context

/** Settings the phone pushes to the watchapp. */
object WatchPrefs {
    private const val PREFS_NAME = "watch"
    private const val KEY_TOUCH_ENABLED = "touch_enabled"

    fun isTouchEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_TOUCH_ENABLED, true)

    fun setTouchEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TOUCH_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
