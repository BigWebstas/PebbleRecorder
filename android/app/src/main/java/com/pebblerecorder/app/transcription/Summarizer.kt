package com.pebblerecorder.app.transcription

import android.content.Context
import android.util.Log
import com.pebblerecorder.app.settings.GeminiPrefs
import com.pebblerecorder.app.settings.SummaryProvider
import com.pebblerecorder.app.settings.TranscriptionPrefs
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "Summarizer"

private const val PROMPT = "Summarize this transcript of a voice recording. Write a short summary " +
    "(two or three sentences), then a bulleted list of action items or follow-ups if there are any. " +
    "Use markdown, only state what the transcript says, and don't add a heading.\n\nTranscript:\n"

// Fast, cheap models - a summary doesn't need the biggest ones.
private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:generateContent"
private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
private const val OPENAI_MODEL = "gpt-4o-mini"
private const val CLAUDE_URL = "https://api.anthropic.com/v1/messages"
private const val CLAUDE_MODEL = "claude-haiku-5-5"

/**
 * Summarizes a finished transcript with a cloud model. This is a separate, opt-in step from
 * transcription: it sends the transcript *text* to the chosen provider even when the recording
 * itself was transcribed on the phone.
 */
object Summarizer {

    suspend fun summarize(context: Context, transcript: String): Result<String> =
        withRetries(TAG) { once(context, PROMPT + transcript) }

    /**
     * Checks that the chosen provider accepts the saved key by asking it for one word. A single
     * attempt - retrying would only hide a bad key or a dead connection from the person testing.
     */
    suspend fun test(context: Context): Result<String> = once(context, "Reply with the single word OK.")

    private suspend fun once(context: Context, prompt: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val provider = TranscriptionPrefs.summaryProvider(context)
                val key = when (provider) {
                    SummaryProvider.GEMINI -> GeminiPrefs.getApiKey(context)
                    SummaryProvider.OPENAI -> TranscriptionPrefs.openAiApiKey(context)
                    SummaryProvider.CLAUDE -> TranscriptionPrefs.claudeApiKey(context)
                } ?: return@withContext Result.failure(IllegalStateException("No ${provider.label} API key configured"))

                val (url, headers, body) = when (provider) {
                    SummaryProvider.GEMINI -> Triple(
                        GEMINI_URL,
                        mapOf("x-goog-api-key" to key),
                        JSONObject().put(
                            "contents",
                            JSONArray().put(
                                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))),
                            ),
                        ).toString(),
                    )
                    SummaryProvider.OPENAI -> Triple(
                        OPENAI_URL,
                        mapOf("Authorization" to "Bearer $key"),
                        JSONObject()
                            .put("model", OPENAI_MODEL)
                            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                            .toString(),
                    )
                    SummaryProvider.CLAUDE -> Triple(
                        CLAUDE_URL,
                        mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
                        JSONObject()
                            .put("model", CLAUDE_MODEL)
                            .put("max_tokens", 1024)
                            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                            .toString(),
                    )
                }

                val (code, response) = try {
                    postJson(url, headers, body)
                } catch (e: IOException) {
                    return@withContext Result.failure(
                        RetryableTranscriptionException("Network error calling the ${provider.label} API", e),
                    )
                }
                if (code !in 200..299) {
                    val message = "${provider.label} API returned HTTP $code: $response"
                    return@withContext Result.failure(
                        if (isRetryableStatus(code)) RetryableTranscriptionException(message) else IllegalStateException(message),
                    )
                }
                Result.success(extract(provider, response).trim())
            } catch (e: Exception) {
                Log.e(TAG, "Summary attempt failed", e)
                Result.failure(e)
            }
        }

    private fun extract(provider: SummaryProvider, response: String): String =
        try {
            val json = JSONObject(response)
            when (provider) {
                SummaryProvider.GEMINI -> json.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                SummaryProvider.OPENAI -> json.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                SummaryProvider.CLAUDE -> json.getJSONArray("content").getJSONObject(0).getString("text")
            }
        } catch (e: Exception) {
            throw IllegalStateException("Unexpected ${provider.label} response shape: $response", e)
        }
}
