package com.pebblerecorder.app.transcription

import android.content.Context
import com.pebblerecorder.app.settings.TranscriptionPrefs
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 16 kHz samples in 30 minutes.
private const val LONG_RECORDING_SAMPLES = 30 * 60 * 16_000

/** Transcribes a recording on the device with whisper.cpp - no network, nothing leaves the phone. */
object WhisperTranscriber {
    // One transcription at a time: each loads a model and uses every fast core.
    private val lock = Any()

    /**
     * Long recordings fall back to Tiny (if downloaded and the user allows it) - a bigger model
     * could take longer than the recording itself. A speaker-labelling model is kept: the user
     * picked it for the labels.
     */
    private fun modelFor(context: Context, selected: WhisperModel, sampleCount: Int): WhisperModel =
        if (TranscriptionPrefs.tinyForLongRecordings(context) &&
            !selected.diarizes && selected != WhisperModel.TINY &&
            sampleCount > LONG_RECORDING_SAMPLES &&
            WhisperModels.isDownloaded(context, WhisperModel.TINY)
        ) WhisperModel.TINY else selected

    suspend fun transcribe(context: Context, audio: File): Result<String> =
        withContext(Dispatchers.Default) {
            runCatching {
                val selected = TranscriptionPrefs.whisperModel(context)
                check(WhisperModels.isDownloaded(context, selected)) {
                    "The ${selected.label} Whisper model isn't downloaded"
                }
                synchronized(lock) {
                    TranscriptionStatus.begin(audio.name)
                    try {
                        val pcm = AudioDecoder.decode(audio)
                        val length = SilenceTrimmer.trim(pcm.data, pcm.size)
                        val model = modelFor(context, selected, length)
                        val modelFile = WhisperModels.file(context, model)
                        val handle = WhisperLib.init(modelFile.path)
                        check(handle != 0L) { "Could not load the ${model.label} Whisper model" }
                        try {
                            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                            val text = WhisperLib.transcribe(
                                handle, pcm.data, length, threads, model.diarizes, TranscriptionStatus::progress,
                            ) ?: error("Whisper failed to transcribe the recording")
                            if (model.diarizes) labelSpeakers(text) else text.trim()
                        } finally {
                            WhisperLib.free(handle)
                        }
                    } finally {
                        TranscriptionStatus.end(audio.name)
                    }
                }
            }
        }
}
