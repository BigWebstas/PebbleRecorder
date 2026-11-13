package com.pebblerecorder.app.transcription

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.pebblerecorder.app.recording.RecordingFormat
import com.pebblerecorder.app.settings.GeminiPrefs
import java.io.File
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
// An inline recording near the size limit can be well over half an hour of speech, which is more
// transcript than 8192 tokens; Gemini Flash allows far more.
private const val MAX_OUTPUT_TOKENS = 32768
private const val MAX_INLINE_AUDIO_BYTES = 19 * 1024 * 1024

/** Sends a recorded file to the Gemini API and returns the transcript, or a failure with details. */
object GeminiTranscriber {

    /** Transcribes [audioUri], retrying transient failures (see [withRetries]). */
    suspend fun transcribe(
        context: Context,
        audioUri: Uri,
        bookmarks: List<Int> = emptyList(),
    ): Result<String> {
        val size = sizeOf(context, audioUri)
        // Unknown size (-1) goes the direct way too; it's checked again once read.
        if (size <= MAX_INLINE_AUDIO_BYTES) {
            return withRetries(TAG) { transcribeOnce(context, audioUri, bookmarks, null) }
        }
        return transcribeInChunks(context, audioUri, bookmarks)
    }

    /**
     * A recording over the inline request limit is re-encoded small and sent in ~15-minute pieces
     * (see [AudioTranscoder]), then the transcripts are joined. Each piece is told where the
     * speakers left off so the labels stay consistent, and bookmarks are mapped onto the piece they
     * fall in and back to whole-recording times.
     */
    private suspend fun transcribeInChunks(
        context: Context,
        audioUri: Uri,
        bookmarks: List<Int>,
    ): Result<String> {
        val dir = File(context.cacheDir, "gemini-parts-${System.nanoTime()}").apply { mkdirs() }
        try {
            val chunks = try {
                withContext(Dispatchers.Default) { AudioTranscoder.split(context, audioUri, dir) }
            } catch (e: Exception) {
                Log.e(TAG, "Could not prepare the recording for Gemini", e)
                return Result.failure(IllegalStateException("Could not prepare the long recording for Gemini: ${e.message}", e))
            }
            if (chunks.isEmpty()) return Result.failure(IllegalStateException("No audio found in the recording"))
            Log.d(TAG, "Sending ${chunks.size} parts to Gemini")
            val parts = mutableListOf<String>()
            for ((i, chunk) in chunks.withIndex()) {
                val relative = ChunkPlanning.bookmarksIn(bookmarks, chunk.startSec, chunk.endSec)
                val part = PartInfo(i + 1, chunks.size, parts.lastOrNull()?.let { ChunkPlanning.tail(it) })
                val text = withRetries(TAG) { transcribeOnce(context, Uri.fromFile(chunk.file), relative, part) }
                    .getOrElse { return Result.failure(it) }
                parts += ChunkPlanning.offsetBookmarkMarkers(text, chunk.startSec)
            }
            return Result.success(ChunkPlanning.join(parts))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Which piece of a longer recording a request is, and how the previous piece ended. */
    private class PartInfo(val number: Int, val total: Int, val previousTail: String?)

    private suspend fun transcribeOnce(
        context: Context,
        audioUri: Uri,
        bookmarks: List<Int>,
        part: PartInfo?,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val apiKey = GeminiPrefs.getApiKey(context)
                    ?: return@withContext Result.failure(IllegalStateException("No Gemini API key configured"))

                // Check before reading: a long recording can be tens of MB, and there's no point
                // pulling it into memory only to refuse it.
                checkSize(sizeOf(context, audioUri), MAX_INLINE_AUDIO_BYTES.toLong(), "Gemini")
                    ?.let { return@withContext Result.failure(it) }

                val audioBytes = context.contentResolver.openInputStream(audioUri)?.use { it.readBytes() }
                    ?: return@withContext Result.failure(IllegalStateException("Could not read recording"))

                checkSize(audioBytes.size.toLong(), MAX_INLINE_AUDIO_BYTES.toLong(), "Gemini")
                    ?.let { return@withContext Result.failure(it) }

                val requestBody = buildRequestBody(
                    audioBytes,
                    RecordingFormat.mimeTypeOf(audioUri.lastPathSegment),
                    bookmarks,
                    part,
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
        partInfo: PartInfo?,
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
        val promptText = if (partInfo == null) prompt else prompt + partNote(partInfo)
        val textPart = JSONObject().put("text", promptText)
        val content = JSONObject().put("parts", JSONArray().put(textPart).put(part))
        // Plain transcription needs no reasoning - thinkingBudget=0 asks the model to skip it, but
        // in practice some responses still burn a chunk of the budget on "thoughts" anyway
        // (seen even with budget=0, especially on very short clips). A generous explicit
        // maxOutputTokens guards against that consuming the whole response with no room left for
        // the actual transcript (finishReason=STOP, zero output tokens).
        val generationConfig = JSONObject()
            .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
        return JSONObject()
            .put("contents", JSONArray().put(content))
            .put("generationConfig", generationConfig)
            .toString()
    }

    private fun partNote(part: PartInfo) = buildString {
        append(
            " This audio is part ${part.number} of ${part.total} of one longer recording, cut at a pause. " +
                "Transcribe only this part, with no introduction or summary.",
        )
        if (part.previousTail != null) {
            append(
                " The previous part ended with the text between the triple quotes - keep the same " +
                    "speaker numbering for people who were already speaking, and don't repeat it:\n\"\"\"\n" +
                    part.previousTail + "\n\"\"\"",
            )
        }
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
