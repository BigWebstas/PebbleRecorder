package com.pebblerecorder.app.transcription

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "WhisperModel"

private const val WHISPER_CPP_REPO = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
private const val TINYDIARIZE_REPO = "https://huggingface.co/akashmjn/tinydiarize-whisper.cpp/resolve/main/"

/**
 * The English-only whisper.cpp models offered for on-device transcription. The SHA-256 pins each
 * download to exactly the file we tested. [diarizes] models are tinydiarize fine-tunes that mark
 * where the speaker changes (see [labelSpeakers]); only that one ships unquantized, hence its size.
 */
enum class WhisperModel(
    val label: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val baseUrl: String = WHISPER_CPP_REPO,
    val diarizes: Boolean = false,
) {
    TINY("Tiny", "ggml-tiny.en-q5_1.bin", 32_166_155L, "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b"),
    BASE("Base", "ggml-base.en-q5_1.bin", 59_721_011L, "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"),
    SMALL("Small", "ggml-small.en-q5_1.bin", 190_098_681L, "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30"),
    SMALL_SPEAKERS(
        "Small + speakers", "ggml-small.en-tdrz.bin", 487_614_184L,
        "ceac3ec06d1d98ef71aec665283564631055fd6129b79d8e1be4f9cc33cc54b4",
        TINYDIARIZE_REPO, diarizes = true,
    ),
}

/** Where the models live on disk, and downloading them. */
object WhisperModels {
    sealed interface Download {
        data class Running(val model: WhisperModel, val bytes: Long) : Download
        data class Failed(val model: WhisperModel, val message: String) : Download
    }

    // Downloads outlive the settings screen: a 190 MB download shouldn't die because the user left.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _download = MutableStateFlow<Download?>(null)

    /** The download in progress (or the last one that failed); null when idle. */
    val download: StateFlow<Download?> = _download.asStateFlow()

    fun file(context: Context, model: WhisperModel) = File(File(context.filesDir, "whisper"), model.fileName)

    fun isDownloaded(context: Context, model: WhisperModel) = file(context, model).length() == model.sizeBytes

    fun delete(context: Context, model: WhisperModel) {
        file(context, model).delete()
    }

    /** Starts downloading [model] unless a download is already running. */
    @Synchronized
    fun startDownload(context: Context, model: WhisperModel) {
        if (_download.value is Download.Running) return
        val appContext = context.applicationContext
        _download.value = Download.Running(model, 0)
        scope.launch {
            _download.value = try {
                download(appContext, model)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Downloading ${model.fileName} failed", e)
                Download.Failed(model, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun download(context: Context, model: WhisperModel) {
        val target = file(context, model)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, model.fileName + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URL(model.baseUrl + model.fileName).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            var bytes = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        bytes += n
                        _download.value = Download.Running(model, bytes)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (bytes != model.sizeBytes || hash != model.sha256) {
                throw IOException("Downloaded file is corrupt (checksum mismatch)")
            }
            if (!part.renameTo(target)) throw IOException("Could not save the model")
        } finally {
            connection.disconnect()
            part.delete()
        }
    }
}
