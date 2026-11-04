package com.pebblerecorder.app.transcription

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.pebblerecorder.app.recording.RecordingFormat
import com.pebblerecorder.app.settings.GeminiPrefs
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "GeminiTranscriber"

// Flash is fast and cheap; swap for e.g. "gemini-3.7-pro" if accuracy matters more than latency.
private const val MODEL = "gemini-3.7-flash"
private const val API_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

// Gemini's inline request cap is 20MB total; stay under it with room for the prompt/JSON overhead.
// Recordings past this would need the separate Files API (resumable upload), which isn't
// implemented here - at this app's ~12kbps mono AAC output that's several hours of audio.
private const val MAX_INLINE_AUDIO_BYTES = 19 * 1024 * 1024

/** Sends a recorded file to the Gemini API and returns the transcript, or a failure with details. */
object GeminiTranscriber {

    /** Transcribes [audioUri], retrying transient failures (see [withRetries]). */
    suspend fun transcribe(
        context: Context,
        audioUri: Uri,
        bookmarks: List<Int> = emptyList(),
    ): Result<String> =
        withRetries(TAG) { transcribeOnce(context, audioUri, bookmarks) }

    private suspend fun transcribeOnce(
        context: Context,
        audioUri: Uri,
        bookmarks: List<Int>,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val apiKey = GeminiPrefs.getApiKey(context)
                    ?: return@withContext Result.failure(IllegalStateException("No Gemini API key configured"))

                val audioBytes = context.contentResolver.openInputStream(audioUri)?.use { it.readBytes() }
                    ?: return@withContext Result.failure(IllegalStateException("Could not read recording"))

                if (audioBytes.size > MAX_INLINE_AUDIO_BYTES) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Recording is ${audioBytes.size} bytes, over Gemini's inline request limit",
                        ),
                    )
                }

                val requestBody = buildRequestBody(
                    audioBytes,
                    RecordingFormat.mimeTypeOf(audioUri.lastPathSegment),
                    bookmarks,
                )
                val (responseCode, responseBody) = try {
                    postToGemini(apiKey, requestBody)
                } catch (e: IOException) {
                    return@withContext Result.failure(
                        RetryableTranscriptionException("Network error calling Gemini API", e),
                    )
                }

                if (responseCode !in 200..299) {
                    val message = "Gemini API returned HTTP $responseCode: $responseBody"
                    // 429 (rate limited) and 5xx (server-side) are worth another try; other 4xx
                    // (bad request, bad key, quota exhausted) won't fix themselves.
                    return@withContext Result.failure(
                        if (isRetryableStatus(responseCode)) {
                            RetryableTranscriptionException(message)
                        } else {
                            IllegalStateException(message)
                        },
                    )
                }

                Result.success(extractTranscript(responseBody))
            } catch (e: Exception) {
                Log.e(TAG, "Transcription attempt failed", e)
                Result.failure(e)
            }
        }

    private fun buildRequestBody(
        audioBytes: ByteArray,
        mimeType: String,
        bookmarks: List<Int>,
    ): String {
        val part = JSONObject().put(
            "inlineData",
            JSONObject()
                .put("mimeType", mimeType)
                .put("data", Base64.encodeToString(audioBytes, Base64.NO_WRAP)),
        )
        val prompt = buildString {
            append(
                "Transcribe this audio recording accurately. Identify distinct speakers and " +
                    "label each line with a consistent speaker tag (**Speaker 1:**, **Speaker 2:**, " +
                    "etc.) in the order they first speak, formatted as markdown. If you cannot " +
                    "distinguish speakers, omit the labels rather than guessing.",
            )
            if (bookmarks.isNotEmpty()) {
                val times = bookmarks.joinToString(", ") { clockText(it) }
                append(
                    " The user bookmarked these timestamps during the recording: $times. " +
                        "Mark each bookmarked point inline in the transcript with **[Bookmark MM:SS]**.",
                )
            }
        }
        val textPart = JSONObject().put("text", prompt)
        val content = JSONObject().put("parts", JSONArray().put(textPart).put(part))
        // Plain transcription needs no reasoning - thinkingBudget=0 asks the model to skip it, but
        // in practice some responses still burn a chunk of the budget on "thoughts" anyway
        // (seen even with budget=0, especially on very short clips). A generous explicit
        // maxOutputTokens guards against that consuming the whole response with no room left for
        // the actual transcript (finishReason=STOP, zero output tokens).
        val generationConfig = JSONObject()
            .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            .put("maxOutputTokens", 8192)
        return JSONObject()
            .put("contents", JSONArray().put(content))
            .put("generationConfig", generationConfig)
            .toString()
    }

    private fun postToGemini(apiKey: String, requestBody: String): Pair<Int, String> {
        val connection = URL(API_URL).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000

            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return responseCode to body
        } finally {
            connection.disconnect()
        }
    }

    private fun extractTranscript(responseBody: String): String =
        try {
            JSONObject(responseBody)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        } catch (e: Exception) {
            throw IllegalStateException("Unexpected Gemini response shape: $responseBody", e)
        }
}
