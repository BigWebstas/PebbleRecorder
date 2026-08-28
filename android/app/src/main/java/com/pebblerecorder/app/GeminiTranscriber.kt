package com.pebblerecorder.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private const val TAG = "GeminiTranscriber"

// Transient failures (network blips, rate limits, server errors) are retried with these delays
// before the 2nd, 3rd and 4th attempts. Permanent failures (bad key, oversized recording, a 4xx
// other than 429, an unparseable response) are not retried.
private val RETRY_BACKOFF_MS = longArrayOf(5_000L, 20_000L, 60_000L)
private const val MAX_ATTEMPTS = 4

/** A transcription failure worth retrying, as opposed to a permanent one. */
private class RetryableTranscriptionException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

// Flash is fast and cheap; swap for e.g. "gemini-3.7-pro" if accuracy matters more than latency.
private const val MODEL = "gemini-3.7-flash"
private const val API_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

// Gemini's inline request cap is 20MB total; stay under it with room for the prompt/JSON overhead.
// Recordings past this would need the separate Files API (resumable upload), which isn't
// implemented here - at this app's ~12kbps mono AAC output that's several hours of audio.
private const val MAX_INLINE_AUDIO_BYTES = 19 * 1024 * 1024

/** Sends a recorded file to the Gemini API and returns the transcript, or a failure with details. */
object GeminiTranscriber {

    /**
     * Transcribes [audioUri], retrying transient failures up to [MAX_ATTEMPTS] times with a
     * backoff between attempts. Returns the transcript on success, or the last failure once the
     * retries are exhausted (or immediately for a permanent failure).
     */
    suspend fun transcribe(context: Context, audioUri: Uri): Result<String> {
        var lastError: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) {
                val backoff = RETRY_BACKOFF_MS[minOf(attempt - 2, RETRY_BACKOFF_MS.lastIndex)]
                Log.w(TAG, "Retrying transcription in ${backoff}ms (attempt $attempt/$MAX_ATTEMPTS)")
                delay(backoff)
            }
            val result = transcribeOnce(context, audioUri)
            if (result.isSuccess || result.exceptionOrNull() !is RetryableTranscriptionException) {
                return result
            }
            lastError = result.exceptionOrNull()
        }
        return Result.failure(
            lastError ?: IllegalStateException("Transcription failed after $MAX_ATTEMPTS attempts"),
        )
    }

    private suspend fun transcribeOnce(context: Context, audioUri: Uri): Result<String> =
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

                val requestBody = buildRequestBody(audioBytes)
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
                        if (responseCode == 429 || responseCode >= 500) {
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

    private fun buildRequestBody(audioBytes: ByteArray): String {
        val part = JSONObject().put(
            "inlineData",
            JSONObject()
                .put("mimeType", "audio/mp4")
                .put("data", Base64.encodeToString(audioBytes, Base64.NO_WRAP)),
        )
        val textPart = JSONObject().put(
            "text",
            "Transcribe this audio recording accurately. Identify distinct speakers and " +
                "label each line with a consistent speaker tag (**Speaker 1:**, **Speaker 2:**, " +
                "etc.) in the order they first speak, formatted as markdown. If you cannot " +
                "distinguish speakers, omit the labels rather than guessing.",
        )
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
