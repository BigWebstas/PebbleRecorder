package com.pebblerecorder.app.transcription

import android.content.Context
import android.net.Uri
import android.util.Log
import com.pebblerecorder.app.recording.RecordingFormat
import com.pebblerecorder.app.settings.TranscriptionPrefs
import java.io.IOException
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "OpenAiTranscriber"
private const val API_URL = "https://api.openai.com/v1/audio/transcriptions"
private const val MODEL = "gpt-4o-mini-transcribe"

// The transcription endpoint rejects uploads over 25MB.
private const val MAX_AUDIO_BYTES = 25L * 1024 * 1024

/** Sends a recorded file to OpenAI's audio transcription API and returns the transcript. */
object OpenAiTranscriber {

    /** Transcribes [audioUri], retrying transient failures (see [withRetries]). */
    suspend fun transcribe(context: Context, audioUri: Uri): Result<String> =
        withRetries(TAG) { transcribeOnce(context, audioUri) }

    private suspend fun transcribeOnce(context: Context, audioUri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val apiKey = TranscriptionPrefs.openAiApiKey(context)
                    ?: return@withContext Result.failure(IllegalStateException("No OpenAI API key configured"))
                checkSize(sizeOf(context, audioUri), MAX_AUDIO_BYTES, "OpenAI")
                    ?.let { return@withContext Result.failure(it) }
                val audio = context.contentResolver.openInputStream(audioUri)?.use { it.readBytes() }
                    ?: return@withContext Result.failure(IllegalStateException("Could not read recording"))
                checkSize(audio.size.toLong(), MAX_AUDIO_BYTES, "OpenAI")
                    ?.let { return@withContext Result.failure(it) }
                val name = audioUri.lastPathSegment ?: "recording.m4a"
                val (code, body) = try {
                    post(apiKey, name, RecordingFormat.mimeTypeOf(name), audio)
                } catch (e: IOException) {
                    return@withContext Result.failure(
                        RetryableTranscriptionException("Network error calling OpenAI API", e),
                    )
                }
                if (code !in 200..299) {
                    val message = "OpenAI API returned HTTP $code: $body"
                    return@withContext Result.failure(
                        if (isRetryableStatus(code)) RetryableTranscriptionException(message)
                        else IllegalStateException(message),
                    )
                }
                Result.success(body.trim())
            } catch (e: Exception) {
                Log.e(TAG, "Transcription attempt failed", e)
                Result.failure(e)
            }
        }

    /** Multipart upload; `response_format=text` makes the reply the bare transcript. */
    private fun post(apiKey: String, fileName: String, mimeType: String, audio: ByteArray): Pair<Int, String> {
        val boundary = "pebble-${UUID.randomUUID()}"
        val connection = URL(API_URL).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.doOutput = true
            connection.setChunkedStreamingMode(64 * 1024)
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000

            connection.outputStream.use { out ->
                fun field(name: String, value: String) = out.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                        .toByteArray(),
                )
                field("model", MODEL)
                field("response_format", "text")
                out.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; " +
                        "filename=\"${fileName.replace("\"", "")}\"\r\nContent-Type: $mimeType\r\n\r\n").toByteArray(),
                )
                out.write(audio)
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return code to (stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
        } finally {
            connection.disconnect()
        }
    }
}
