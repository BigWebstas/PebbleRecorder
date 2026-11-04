package com.pebblerecorder.app.settings

import android.content.Context
import org.json.JSONObject

private const val FORMAT_VERSION = 1

/**
 * Exports and imports the app's settings as a small JSON file. Only a fixed list of keys travels:
 * API keys, the webhook token and the recording folder stay behind - a key shouldn't sit in a file
 * that gets emailed around, and a folder permission can't move to another phone anyway.
 */
object SettingsBackup {
    // Preferences file -> the keys in it that are safe to export.
    internal val EXPORTED: Map<String, Set<String>> = mapOf(
        "recording_prefs" to setOf("format", "quality", "mic", "max_minutes", "silence_minutes", "auto_delete_days"),
        "quiet_mode" to setOf("enabled"),
        "watch" to setOf("touch_enabled"),
        "webhook_prefs" to setOf("enabled"),
        "gemini_prefs" to setOf("enabled", "location_enabled"),
        "transcription_prefs" to setOf(
            "engine", "whisper_model", "tiny_for_long", "summary_enabled", "summary_provider",
        ),
    )

    fun export(context: Context): String {
        val files = JSONObject()
        for ((file, keys) in EXPORTED) {
            val values = JSONObject()
            context.getSharedPreferences(file, Context.MODE_PRIVATE).all
                .filterKeys { it in keys }
                .forEach { (key, value) -> values.put(key, value) }
            files.put(file, values)
        }
        return JSONObject().put("version", FORMAT_VERSION).put("settings", files).toString(2)
    }

    /** Applies [json]; returns how many settings were restored, or null if it isn't one of our files. */
    fun import(context: Context, json: String): Int? {
        val files = runCatching { JSONObject(json).getJSONObject("settings") }.getOrNull() ?: return null
        var restored = 0
        for ((file, allowed) in EXPORTED) {
            val values = files.optJSONObject(file) ?: continue
            val edit = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
            for (key in allowed) {
                if (!values.has(key)) continue
                when (val value = values.get(key)) {
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is String -> edit.putString(key, value)
                    else -> continue
                }
                restored++
            }
            edit.apply()
        }
        return restored
    }
}
