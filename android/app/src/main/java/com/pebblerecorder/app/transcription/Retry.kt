package com.pebblerecorder.app.transcription

import android.util.Log
import kotlinx.coroutines.delay

// Transient failures (network blips, rate limits, server errors) are retried with these delays
// before the 2nd, 3rd and 4th attempts. Permanent failures (bad key, oversized recording, a 4xx
// other than 429, an unparseable response) are not retried.
private val RETRY_BACKOFF_MS = longArrayOf(5_000L, 20_000L, 60_000L)
private const val MAX_ATTEMPTS = 4

/** A transcription failure worth retrying, as opposed to a permanent one. */
internal class RetryableTranscriptionException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** HTTP statuses worth another try: 429 (rate limited) and 5xx (server-side); other 4xx won't fix themselves. */
internal fun isRetryableStatus(code: Int) = code == 429 || code >= 500

/**
 * Runs [attempt] up to [MAX_ATTEMPTS] times with a backoff between tries, as long as it keeps
 * failing with a [RetryableTranscriptionException]. Returns the first success, or the last
 * failure once the retries are exhausted (or immediately for a permanent failure).
 */
internal suspend fun <T> withRetries(tag: String, attempt: suspend () -> Result<T>): Result<T> {
    var lastError: Throwable? = null
    for (n in 1..MAX_ATTEMPTS) {
        if (n > 1) {
            val backoff = RETRY_BACKOFF_MS[minOf(n - 2, RETRY_BACKOFF_MS.lastIndex)]
            Log.w(tag, "Retrying transcription in ${backoff}ms (attempt $n/$MAX_ATTEMPTS)")
            delay(backoff)
        }
        val result = attempt()
        if (result.isSuccess || result.exceptionOrNull() !is RetryableTranscriptionException) return result
        lastError = result.exceptionOrNull()
    }
    return Result.failure(lastError ?: IllegalStateException("Transcription failed after $MAX_ATTEMPTS attempts"))
}
