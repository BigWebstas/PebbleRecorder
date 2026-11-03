package com.pebblerecorder.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.SecureRandom

const val WEBHOOK_PORT = 47630
const val WEBHOOK_PREFS_NAME = "webhook_prefs"
private const val KEY_ENABLED = "enabled"
private const val KEY_TOKEN = "token"

/**
 * Settings for the local webhook (see [WebhookServer]). Off by default. The bearer token lives in
 * its own SharedPreferences file, excluded from backup like the Gemini key (backup_rules.xml /
 * data_extraction_rules.xml), and is generated on first use.
 */
object WebhookPrefs {
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun token(context: Context): String =
        prefs(context).getString(KEY_TOKEN, null) ?: regenerateToken(context)

    fun regenerateToken(context: Context): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    /** Lets the service restart the server when the switch flips (the token is read per request). */
    fun registerListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)

    private fun prefs(context: Context) = context.getSharedPreferences(WEBHOOK_PREFS_NAME, Context.MODE_PRIVATE)
}
