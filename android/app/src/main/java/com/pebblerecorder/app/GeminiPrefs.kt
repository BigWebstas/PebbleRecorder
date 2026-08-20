package com.pebblerecorder.app

import android.content.Context

private const val PREFS_NAME = "gemini_prefs"
private const val KEY_ENABLED = "enabled"
private const val KEY_API_KEY = "api_key"
private const val KEY_LOCATION_ENABLED = "location_enabled"

/**
 * Stored in a dedicated (plaintext) SharedPreferences file, excluded from Android backup/device
 * transfer via backup_rules.xml / data_extraction_rules.xml - protected only by Android's normal
 * per-app storage sandboxing, same as most apps' API tokens.
 */
object GeminiPrefs {
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getApiKey(context: Context): String? =
        prefs(context).getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(context: Context, apiKey: String) {
        prefs(context).edit().putString(KEY_API_KEY, apiKey).apply()
    }

    fun isLocationEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCATION_ENABLED, false)

    fun setLocationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCATION_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
