package com.pebblerecorder.app.transcription

import android.content.Context
import com.pebblerecorder.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the transcriber is working on right now, for the main screen and the progress notification. */
object TranscriptionStatus {
    /** The step a transcription is on. */
    enum class Stage {
        /** On-device: decoding the audio before whisper starts. */
        PREPARING,
        /** On-device: whisper is running. */
        WHISPER,
        /** A cloud engine: re-encoding a long recording into pieces. */
        TRANSCODING,
        /** A cloud engine: the audio is with the provider; [Active.part] of [Active.parts] when in pieces. */
        SENDING,
    }

    /** [percent] is null while there's nothing to measure yet. [engine] names the cloud provider. */
    data class Active(
        val name: String,
        val startedAtMs: Long,
        val percent: Int?,
        val stage: Stage = Stage.PREPARING,
        val engine: String? = null,
        val part: Int = 0,
        val parts: Int = 0,
    ) {
        /** What it's doing, without the percentage. */
        fun label(context: Context): String = when (stage) {
            Stage.PREPARING -> context.getString(R.string.transcription_progress_preparing)
            Stage.WHISPER -> context.getString(R.string.transcription_stage_whisper)
            Stage.TRANSCODING -> context.getString(R.string.transcription_stage_transcoding, engine ?: "")
            Stage.SENDING ->
                if (parts > 1) context.getString(R.string.transcription_stage_sending_part, part, parts, engine ?: "")
                else context.getString(R.string.transcription_stage_sending, engine ?: "")
        }

        /** The notification title. */
        fun title(context: Context): String =
            if (engine == null) context.getString(R.string.transcription_progress_title)
            else context.getString(R.string.transcription_progress_title_cloud, engine)
    }

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active

    /** [engine] is set for a cloud engine, null for the on-device one. */
    fun begin(name: String, engine: String? = null) {
        _active.value = Active(name, System.currentTimeMillis(), null, if (engine == null) Stage.PREPARING else Stage.SENDING, engine)
    }

    /** Whisper reports a percentage once it's running. */
    fun progress(percent: Int) {
        _active.value = _active.value?.copy(percent = percent, stage = Stage.WHISPER)
    }

    /** Re-encoding a long recording for a cloud engine, [percent] of the way through the audio. */
    fun transcoding(percent: Int) {
        _active.value = _active.value?.copy(percent = percent, stage = Stage.TRANSCODING)
    }

    /** Sending piece [part] of [parts] (1 of 1 for a recording sent whole); pieces done so far set the bar. */
    fun sending(part: Int, parts: Int) {
        _active.value = _active.value?.copy(
            stage = Stage.SENDING,
            part = part,
            parts = parts,
            percent = if (parts > 1) (part - 1) * 100 / parts else null,
        )
    }

    /** Ends the status for [name]; another transcription's status is left alone. */
    fun end(name: String? = null) {
        if (name == null || _active.value?.name == name) _active.value = null
    }
}
