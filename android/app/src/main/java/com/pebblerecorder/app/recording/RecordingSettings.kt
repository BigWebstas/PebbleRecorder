package com.pebblerecorder.app.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import com.pebblerecorder.app.R

private const val PREFS_NAME = "recording_prefs"
private const val KEY_FORMAT = "format"
private const val KEY_QUALITY = "quality"
private const val KEY_MIC = "mic"

/** Container + codec a recording is written in. */
enum class RecordingFormat(
    val extension: String,
    val mimeType: String,
    val labelRes: Int,
    private val minSdk: Int,
) {
    AAC("m4a", "audio/mp4", R.string.format_aac, Build.VERSION_CODES.O),
    OPUS("ogg", "audio/ogg", R.string.format_opus, Build.VERSION_CODES.Q);

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= minSdk

    /** Sets output format, encoder, sample rate and bitrate; call before setOutputFile/prepare. */
    @SuppressLint("InlinedApi") // OGG/OPUS constants are inlined; the OPUS entry is hidden below API 29.
    fun configure(recorder: MediaRecorder, quality: RecordingQuality) {
        when (this) {
            AAC -> {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioSamplingRate(quality.aacSampleRate)
                recorder.setAudioEncodingBitRate(quality.aacBitRate)
            }
            OPUS -> {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                recorder.setAudioSamplingRate(quality.opusSampleRate)
                recorder.setAudioEncodingBitRate(quality.opusBitRate)
            }
        }
        recorder.setAudioChannels(1)
    }

    companion object {
        /** MIME type for a recording file name; unknown extensions are treated as the default AAC. */
        fun mimeTypeOf(fileName: String?): String =
            entries.firstOrNull { it.extension == fileName?.substringAfterLast('.', "") }?.mimeType ?: AAC.mimeType

        fun isRecordingFile(fileName: String): Boolean =
            entries.any { it.extension == fileName.substringAfterLast('.', "") }
    }
}

/** Mono speech-oriented presets; higher quality means bigger files (and bigger Gemini uploads). */
enum class RecordingQuality(
    val labelRes: Int,
    val aacSampleRate: Int,
    val aacBitRate: Int,
    val opusSampleRate: Int,
    val opusBitRate: Int,
) {
    LOW(R.string.quality_low, 16_000, 32_000, 16_000, 24_000),
    STANDARD(R.string.quality_standard, 44_100, 96_000, 48_000, 64_000),
    HIGH(R.string.quality_high, 48_000, 192_000, 48_000, 128_000),
}

/** A selectable microphone: [key] is stable across reconnects, unlike [AudioDeviceInfo.getId]. */
data class MicOption(val key: String, val label: String, val device: AudioDeviceInfo)

object RecordingSettings {
    // Bluetooth headsets are left out: they need SCO routing and the Bluetooth permission.
    private val SELECTABLE_MIC_TYPES = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    fun format(context: Context): RecordingFormat =
        RecordingFormat.entries.firstOrNull { it.name == prefs(context).getString(KEY_FORMAT, null) && it.isSupported }
            ?: RecordingFormat.AAC

    fun setFormat(context: Context, format: RecordingFormat) {
        prefs(context).edit().putString(KEY_FORMAT, format.name).apply()
    }

    fun quality(context: Context): RecordingQuality =
        RecordingQuality.entries.firstOrNull { it.name == prefs(context).getString(KEY_QUALITY, null) }
            ?: RecordingQuality.STANDARD

    fun setQuality(context: Context, quality: RecordingQuality) {
        prefs(context).edit().putString(KEY_QUALITY, quality.name).apply()
    }

    /** Saved microphone key, or null for "Automatic" (whatever Android picks). */
    fun micKey(context: Context): String? = prefs(context).getString(KEY_MIC, null)

    fun setMicKey(context: Context, key: String?) {
        prefs(context).edit().putString(KEY_MIC, key).apply()
    }

    fun availableMics(context: Context): List<MicOption> =
        context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type in SELECTABLE_MIC_TYPES }
            .map { MicOption("${it.type}:${it.address}", micLabel(context, it), it) }

    /** The saved mic if it's connected right now, else null (= let Android choose). */
    fun preferredMic(context: Context): AudioDeviceInfo? {
        val key = micKey(context) ?: return null
        return availableMics(context).firstOrNull { it.key == key }?.device
    }

    private fun micLabel(context: Context, device: AudioDeviceInfo): String {
        val kind = context.getString(
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> R.string.mic_builtin
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> R.string.mic_wired
                else -> R.string.mic_usb
            },
        )
        val name = device.productName?.toString().orEmpty()
        val detail = listOf(name, device.address).filter { it.isNotBlank() && it != kind }.joinToString(" ")
        return if (detail.isEmpty()) kind else "$kind ($detail)"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
