package com.pebblerecorder.app.settings

import android.content.Context
import com.pebblerecorder.app.transcription.WhisperModel

private const val PREFS_NAME = "transcription_prefs"
private const val KEY_ENGINE = "engine"
private const val KEY_OPENAI_API_KEY = "openai_api_key"
private const val KEY_WHISPER_MODEL = "whisper_model"
private const val KEY_TINY_FOR_LONG = "tiny_for_long"
private const val KEY_SUMMARY_ENABLED = "summary_enabled"
private const val KEY_SUMMARY_PROVIDER = "summary_provider"
private const val KEY_CLAUDE_API_KEY = "claude_api_key"

/** Who writes the optional summary: a text model, separate from whoever transcribed the audio. */
enum class SummaryProvider(val label: String) { GEMINI("Gemini"), OPENAI("OpenAI"), CLAUDE("Claude") }

/** Who turns a recording into text once "Transcribe recordings" is on. */
enum class TranscriptionEngine { GEMINI, OPENAI, WHISPER }

/**
 * Engine choice, the OpenAI key and the on-device model. Kept out of backups with the other
 * key-bearing prefs (backup_rules.xml / data_extraction_rules.xml). Whether transcription is on at
 * all, the Gemini key and the location option still live in [GeminiPrefs], which predates engines.
 */
object TranscriptionPrefs {
    fun engine(context: Context): TranscriptionEngine =
        prefs(context).getString(KEY_ENGINE, null)
            ?.let { name -> TranscriptionEngine.entries.firstOrNull { it.name == name } }
            ?: TranscriptionEngine.GEMINI

    fun setEngine(context: Context, engine: TranscriptionEngine) {
        prefs(context).edit().putString(KEY_ENGINE, engine.name).apply()
    }

    fun openAiApiKey(context: Context): String? =
        prefs(context).getString(KEY_OPENAI_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun setOpenAiApiKey(context: Context, apiKey: String) {
        prefs(context).edit().putString(KEY_OPENAI_API_KEY, apiKey).apply()
    }

    fun whisperModel(context: Context): WhisperModel =
        prefs(context).getString(KEY_WHISPER_MODEL, null)
            ?.let { name -> WhisperModel.entries.firstOrNull { it.name == name } }
            ?: WhisperModel.BASE

    fun setWhisperModel(context: Context, model: WhisperModel) {
        prefs(context).edit().putString(KEY_WHISPER_MODEL, model.name).apply()
    }

    /** Use the Tiny model for recordings over 30 minutes (if it's downloaded). On by default. */
    fun tinyForLongRecordings(context: Context): Boolean = prefs(context).getBoolean(KEY_TINY_FOR_LONG, true)

    fun setTinyForLongRecordings(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TINY_FOR_LONG, enabled).apply()
    }

    fun summaryEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SUMMARY_ENABLED, false)

    fun setSummaryEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SUMMARY_ENABLED, enabled).apply()
    }

    fun summaryProvider(context: Context): SummaryProvider =
        prefs(context).getString(KEY_SUMMARY_PROVIDER, null)
            ?.let { name -> SummaryProvider.entries.firstOrNull { it.name == name } }
            ?: SummaryProvider.CLAUDE

    fun setSummaryProvider(context: Context, provider: SummaryProvider) {
        prefs(context).edit().putString(KEY_SUMMARY_PROVIDER, provider.name).apply()
    }

    fun claudeApiKey(context: Context): String? =
        prefs(context).getString(KEY_CLAUDE_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun setClaudeApiKey(context: Context, apiKey: String) {
        prefs(context).edit().putString(KEY_CLAUDE_API_KEY, apiKey).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
