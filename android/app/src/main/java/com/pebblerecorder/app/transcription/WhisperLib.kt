package com.pebblerecorder.app.transcription

/** JNI bindings to whisper.cpp (see src/main/cpp/whisper_jni.cpp). */
internal object WhisperLib {
    init {
        System.loadLibrary("pebblewhisper")
    }

    /** Loads the ggml model at [modelPath]; 0 if it couldn't be loaded. */
    @JvmStatic external fun init(modelPath: String): Long

    @JvmStatic external fun free(handle: Long)

    const val SPEAKER_TURN_MARKER = "[SPEAKER_TURN]"

    /**
     * Transcribes the first [length] of the 16 kHz mono 16-bit [samples] (with a diarizing [WhisperModel], [diarize] adds
     * [SPEAKER_TURN_MARKER] where the speaker changes), reporting 0-100 to [progress]; null if whisper failed. */
    @JvmStatic external fun transcribe(handle: Long, samples: ShortArray, length: Int, threads: Int, diarize: Boolean, progress: Progress): String?

    /** Called from the transcribing thread as whisper works through the audio. */
    fun interface Progress {
        fun onProgress(percent: Int)
    }
}
