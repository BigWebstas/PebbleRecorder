package com.pebblerecorder.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

private const val SAMPLE_RATE = 16_000
private const val FRAME = 1024
private const val HOP = 512

/** Rows in each spectrum column handed to the UI: [FRAME]/2 FFT bins (0-8 kHz) averaged down. */
const val SPECTRUM_ROWS = 64

// Full-scale sine = 0 dB. Quiet rooms sit near the floor, loud speech near the ceiling.
private const val DB_FLOOR = -90f
private const val DB_CEIL = -20f

private val WINDOW = FloatArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1))).toFloat() }

/**
 * Live microphone spectrum for the on-screen spectrogram. Opens its own [AudioRecord] while
 * collected, separate from the MediaRecorder that writes the actual recording - Android 10+ lets
 * one app capture the mic twice, which is why MainActivity only collects this on API 29+. Emits
 * one column (low to high frequency, each 0..1) per [HOP] samples and releases the mic when the
 * collector is cancelled. Needs RECORD_AUDIO.
 */
@SuppressLint("MissingPermission")
fun micSpectrum(): Flow<FloatArray> = flow {
    val minBuffer = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
    )
    val record = AudioRecord(
        MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FRAME * 4),
    )
    try {
        if (record.state != AudioRecord.STATE_INITIALIZED) return@flow
        record.startRecording()
        val samples = FloatArray(FRAME)
        val hop = ShortArray(HOP)
        val re = FloatArray(FRAME)
        val im = FloatArray(FRAME)
        while (true) {
            if (record.read(hop, 0, HOP) <= 0) break
            // Slide the frame left by one hop and append the new samples.
            System.arraycopy(samples, HOP, samples, 0, FRAME - HOP)
            for (i in 0 until HOP) samples[FRAME - HOP + i] = hop[i] / 32768f
            for (i in 0 until FRAME) {
                re[i] = samples[i] * WINDOW[i]
                im[i] = 0f
            }
            fft(re, im)
            emit(toColumn(re, im))
        }
    } finally {
        record.release()
    }
}.flowOn(Dispatchers.IO)

private fun toColumn(re: FloatArray, im: FloatArray): FloatArray {
    val binsPerRow = FRAME / 2 / SPECTRUM_ROWS
    return FloatArray(SPECTRUM_ROWS) { row ->
        var sum = 0f
        for (b in row * binsPerRow until (row + 1) * binsPerRow) {
            // /(FRAME/4): a full-scale sine under the Hann window peaks at FRAME/4.
            val amplitude = sqrt(re[b] * re[b] + im[b] * im[b]) / (FRAME / 4f)
            sum += amplitude
        }
        val db = 20f * log10(sum / binsPerRow + 1e-9f)
        ((db - DB_FLOOR) / (DB_CEIL - DB_FLOOR)).coerceIn(0f, 1f)
    }
}

/** In-place radix-2 Cooley-Tukey FFT; both arrays must be [FRAME] long. */
private fun fft(re: FloatArray, im: FloatArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            re[i] = re[j].also { re[j] = re[i] }
            im[i] = im[j].also { im[j] = im[i] }
        }
    }
    var len = 2
    while (len <= n) {
        val angle = -2.0 * PI / len
        val wRe = cos(angle).toFloat()
        val wIm = sin(angle).toFloat()
        for (start in 0 until n step len) {
            var curRe = 1f
            var curIm = 0f
            for (k in 0 until len / 2) {
                val a = start + k
                val b = a + len / 2
                val tRe = re[b] * curRe - im[b] * curIm
                val tIm = re[b] * curIm + im[b] * curRe
                re[b] = re[a] - tRe
                im[b] = im[a] - tIm
                re[a] += tRe
                im[a] += tIm
                val nextRe = curRe * wRe - curIm * wIm
                curIm = curRe * wIm + curIm * wRe
                curRe = nextRe
            }
        }
        len = len shl 1
    }
}
